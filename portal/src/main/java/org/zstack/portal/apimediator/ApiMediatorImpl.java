package org.zstack.portal.apimediator;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.core.thread.SyncTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.AbstractService;
import org.zstack.header.apimediator.*;
import org.zstack.header.identity.AuthorizationInfo;
import org.zstack.header.identity.CredentialChecker;
import org.zstack.header.identity.CredentialDeniedException;
import org.zstack.header.identity.SuppressCredentialCheck;
import org.zstack.header.managementnode.IsManagementNodeReadyMsg;
import org.zstack.header.managementnode.IsManagementNodeReadyReply;
import org.zstack.header.managementnode.ManagementNodeConstant;
import org.zstack.header.message.*;
import org.zstack.utils.StringDSL;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;

import java.util.*;

import static org.zstack.utils.CollectionDSL.e;
import static org.zstack.utils.CollectionDSL.list;
import static org.zstack.utils.CollectionDSL.map;

public class ApiMediatorImpl extends AbstractService implements ApiMediator, GlobalApiMessageInterceptor {
    private static final CLogger logger = Utils.getLogger(ApiMediator.class);

    @Autowired
    private CloudBus bus;
    @Autowired
    private CredentialChecker cchecker;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private ErrorFacade errf;

    private ApiMessageProcessor processor;

    private List<String> serviceConfigFolders;
    private int apiWorkerNum = 5;

    private void dispatchMessage(APIMessage msg) {
        ApiMessageDescriptor desc = processor.getApiMessageDescriptor(msg);
        if (desc == null) {
            Map message = map(e(msg.getClass().getName(), msg));
            String  err = String.format("no service configuration file declares message: %s", JSONObjectUtil.toJsonString(message));
            logger.warn(err);
            bus.replyErrorByMessageType(msg, errf.instantiateErrorCode(PortalErrors.NO_SERVICE_FOR_MESSAGE, err));
            return;
        }

        if (!desc.getClazz().isAnnotationPresent(SuppressCredentialCheck.class)) {
            try {
                AuthorizationInfo info = new AuthorizationInfo();
                info.setNeedRoles(desc.getRoles());
                cchecker.authenticateAndAuthorize(msg, info);
            } catch (CredentialDeniedException e) {
                logger.trace("", e);
                bus.replyErrorByMessageType(msg, e.getError());
                return;
            }
        }

        try {
            msg.setServiceId(null);
            msg = processor.process(msg);
        } catch (ApiMessageInterceptionException ie) {
            logger.debug(ie.getError().toString(), ie);
            bus.replyErrorByMessageType(msg, ie.getError());
            return;
        } catch (StopRoutingException e) {
            return;
        }

        if (msg.getServiceId() == null && desc.getServiceId() != null) {
            bus.makeLocalServiceId(msg, desc.getServiceId());
        }

        if (msg.getServiceId() == null) {
            String err = String.format("No service id found for API message[%s], message dump: %s", msg.getMessageName(), JSONObjectUtil.toJsonString(msg));
            logger.warn(err);
            bus.replyErrorByMessageType(msg, errf.stringToInternalError(err));
            return;
        }

        bus.route(msg);
    }


    @Override
    public void handleMessage(final Message msg) {
        thdf.syncSubmit(new SyncTask<Object>() {
            @Override
            public String getSyncSignature() {
                return "api.worker";
            }

            @Override
            public int getSyncLevel() {
                return apiWorkerNum;
            }

            @Override
            public String getName() {
                return "api.worker";
            }

            @Override
            public Object call() throws Exception {
                if (msg.getClass() == APIIsReadyToGoMsg.class) {
                    handle((APIIsReadyToGoMsg) msg);
                } else {
                    try {
                        dispatchMessage((APIMessage) msg);
                    } catch (Throwable t) {
                        bus.logExceptionWithMessageDump(msg, t);
                        bus.replyErrorByMessageType(msg, errf.throwableToInternalError(t));
                    }
                }

                return null;
            }
        });
    }

    private void handle(final APIIsReadyToGoMsg msg) {
        final APIIsReadyToGoReply areply = new APIIsReadyToGoReply();

        IsManagementNodeReadyMsg imsg = new IsManagementNodeReadyMsg();
        String nodeId = msg.getManagementNodeId();
        if (nodeId == null) {
            bus.makeLocalServiceId(imsg, ManagementNodeConstant.SERVICE_ID);
            nodeId = Platform.getManagementServerId();
        } else {
            bus.makeServiceIdByManagementNodeId(imsg, ManagementNodeConstant.SERVICE_ID, msg.getManagementNodeId());
        }

        final String fnodeId = nodeId;
        areply.setManagementNodeId(nodeId);
        bus.send(imsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    areply.setError(reply.getError());
                } else {
                    IsManagementNodeReadyReply r = (IsManagementNodeReadyReply) reply;
                    if (!r.isReady()) {
                        areply.setError(errf.instantiateErrorCode(SysErrors.NOT_READY_ERROR,
                                String.format("management node[uuid:%s] is not ready yet", fnodeId)));
                    }
                }
                bus.reply(msg, areply);
            }
        });
    }

    @Override
    public String getId() {
        return ApiMediatorConstant.SERVICE_ID;
    }

    @Override
    public boolean start() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("serviceConfigFolders", serviceConfigFolders);
        processor = new ApiMessageProcessorImpl(config);
        bus.registerService(this);
        return true;
    }

    @Override
    public boolean stop() {
        bus.unregisterService(this);
        return true;
    }

    public void setServiceConfigFolders(List<String> serviceConfigFolders) {
        this.serviceConfigFolders = serviceConfigFolders;
    }

    public void setApiWorkerNum(int apiWorkerNum) {
        this.apiWorkerNum = apiWorkerNum;
    }

    @Override
    public List<Class> getMessageClassToIntercept() {
        List<Class> lst = new ArrayList<Class>();
        lst.add(APICreateMessage.class);
        return lst;
    }

    @Override
    public InterceptorPosition getPosition() {
        return InterceptorPosition.FRONT;
    }

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        if (msg instanceof APICreateMessage) {
            APICreateMessage cmsg = (APICreateMessage) msg;
            if (cmsg.getResourceUuid() != null) {
                if (!StringDSL.isZstackUuid(cmsg.getResourceUuid())) {
                    throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                            String.format("resourceUuid[%s] is not a valid uuid. A valid uuid is a UUID(v4 recommended) with '-' stripped. " +
                                    "see http://en.wikipedia.org/wiki/Universally_unique_identifier for format of UUID, the regular expression ZStack uses" +
                                    " to validate a UUID is '[0-9a-f]{8}[0-9a-f]{4}[1-5][0-9a-f]{3}[89ab][0-9a-f]{3}[0-9a-f]{12}'", cmsg.getResourceUuid())
                    ));
                }
            }
        }
        return msg;
    }
}
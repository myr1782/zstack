package org.zstack.compute.vm;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.Platform;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.statemachine.StateMachine;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.message.Message;
import org.zstack.header.vm.*;
import org.zstack.utils.message.OperationChecker;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public abstract class AbstractVmInstance implements VmInstance {
    protected static OperationChecker allowedOperations = new OperationChecker(true);
    protected static OperationChecker stateChangeChecker = new OperationChecker(false);

    @Autowired
    protected ErrorFacade errf;

    static {
        allowedOperations.addState(VmInstanceState.Created,
                StartNewCreatedVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Running,
                APIStopVmInstanceMsg.class.getName(),
                StopVmInstanceMsg.class.getName(),
                APIRebootVmInstanceMsg.class.getName(),
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName(),
                APIMigrateVmMsg.class.getName(),
                MigrateVmMsg.class.getName(),
                AttachDataVolumeToVmMsg.class.getName(),
                DetachDataVolumeFromVmMsg.class.getName(),
                AttachNicToVmMsg.class.getName(),
                VmAttachNicMsg.class.getName(),
                APIAttachNicToVmMsg.class.getName(),
                GetVmMigrationTargetHostMsg.class.getName(),
                APIGetVmMigrationCandidateHostsMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Stopped,
                APIStopVmInstanceMsg.class.getName(),
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName(),
                APIStartVmInstanceMsg.class.getName(),
                StartVmInstanceMsg.class.getName(),
                AttachDataVolumeToVmMsg.class.getName(),
                DetachDataVolumeFromVmMsg.class.getName(),
                CreateTemplateFromVmRootVolumeMsg.class.getName(),
                VmAttachNicMsg.class.getName(),
                APIAttachNicToVmMsg.class.getName(),
                StopVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Unknown,
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName(),
                APIStopVmInstanceMsg.class.getName(),
                StopVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Starting,
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Migrating,
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Stopping,
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName());

        allowedOperations.addState(VmInstanceState.Rebooting,
                APIDestroyVmInstanceMsg.class.getName(),
                DestroyVmInstanceMsg.class.getName());

        stateChangeChecker.addState(VmInstanceStateEvent.unknown.toString(),
                VmInstanceState.Created.toString(),
                VmInstanceState.Stopped.toString(),
                VmInstanceState.Destroyed.toString(),
                VmInstanceState.Expunging.toString());
    }


    private ErrorCode validateOperationByState(OperationChecker checker, Message msg, VmInstanceState currentState, Enum errorCode) {
        if (checker.isOperationAllowed(msg.getMessageName(), currentState.toString())) {
            return null;
        } else {
            String details = String.format("current vm instance state[%s] doesn't allow to proceed message[%s], allowed states are %s", currentState,
                    msg.getMessageName(), checker.getStatesForOperation(msg.getMessageName()));
            ErrorCode cause = errf.instantiateErrorCode(VmErrors.NOT_IN_CORRECT_STATE, details);
            if (errorCode != null) {
                return errf.instantiateErrorCode(errorCode, cause);
            } else {
                return cause;
            }
        }
    }
    
    public ErrorCode validateOperationByState(Message msg, VmInstanceState currentState, Enum errorCode) {
        return validateOperationByState(allowedOperations, msg, currentState, errorCode);
    }
    
    public static boolean needChangeState(OperationChecker checker, VmInstanceStateEvent stateEvent, VmInstanceState currentState) {
        return checker.isOperationAllowed(stateEvent.toString(), currentState.toString(), false);
    }
    
    public static boolean needChangeState(VmInstanceStateEvent stateEvent, VmInstanceState currentState) {
        return needChangeState(stateChangeChecker, stateEvent, currentState);
    }
    
}
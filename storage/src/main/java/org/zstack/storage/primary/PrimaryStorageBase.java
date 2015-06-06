package org.zstack.storage.primary;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.cascade.CascadeConstant;
import org.zstack.core.cascade.CascadeFacade;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.NopeCompletion;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.core.inventory.InventoryFacade;
import org.zstack.core.job.JobQueueFacade;
import org.zstack.core.workflow.*;
import org.zstack.header.core.Completion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.message.APIDeleteMessage;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.storage.primary.*;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import javax.persistence.LockModeType;
import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE, dependencyCheck = true)
public abstract class PrimaryStorageBase extends AbstractPrimaryStorage {
    private final static CLogger logger = Utils.getLogger(PrimaryStorageBase.class);

	protected PrimaryStorageVO self;

	@Autowired
	protected CloudBus bus;
	@Autowired
	protected DatabaseFacade dbf;
	@Autowired
	protected JobQueueFacade jobf;
    @Autowired
    protected PrimaryStorageExtensionPointEmitter extpEmitter;
    @Autowired
    protected InventoryFacade invf;
    @Autowired
    protected CascadeFacade casf;
    @Autowired
    protected ErrorFacade errf;
    @Autowired
    protected ThreadFacade thdf;


	protected abstract void handle(InstantiateVolumeMsg msg);
	
	protected abstract void handle(DeleteVolumeOnPrimaryStorageMsg msg);
	
	protected abstract void handle(CreateTemplateFromVolumeOnPrimaryStorageMsg msg);

    protected abstract void handle(DownloadDataVolumeToPrimaryStorageMsg msg);

    protected abstract void handle(DeleteBitsOnPrimaryStorageMsg msg);

    protected abstract void handle(DownloadIsoToPrimaryStorageMsg msg);

    protected abstract void connectHook(ConnectPrimaryStorageMsg msg, Completion completion);

	public PrimaryStorageBase(PrimaryStorageVO self) {
		this.self = self;
	}

    protected PrimaryStorageInventory getSelfInventory() {
        return PrimaryStorageInventory.valueOf(self);
    }

    protected String getSyncId() {
        return String.format("primaryStorage-%s", self.getUuid());
    }

    @Override
    public void attachHook(String clusterUuid, Completion completion) {
        completion.success();
    }

    @Override
    public void detachHook(String clusterUuid, Completion completion) {
        completion.success();
    }

    @Override
    public void deleteHook() {
    }

    @Override
    public void changeStateHook(PrimaryStorageStateEvent evt, PrimaryStorageState nextState) {
    }

	@Override
	public void handleMessage(Message msg) {
		try {
			if (msg instanceof APIMessage) {
				handleApiMessage((APIMessage) msg);
			} else {
				handleLocalMessage(msg);
			}
		} catch (Exception e) {
			bus.logExceptionWithMessageDump(msg, e);
			bus.replyErrorByMessageType(msg, e);
		}
	}

	protected void handleLocalMessage(Message msg) {
	    if (msg instanceof PrimaryStorageReportCapacityMsg) {
	        handle((PrimaryStorageReportCapacityMsg) msg);
	    } else if (msg instanceof InstantiateVolumeMsg) {
	        handle((InstantiateVolumeMsg)msg);
	    } else if (msg instanceof DeleteVolumeOnPrimaryStorageMsg) {
	        handle((DeleteVolumeOnPrimaryStorageMsg)msg);
	    } else if (msg instanceof CreateTemplateFromVolumeOnPrimaryStorageMsg) {
	        handleBase((CreateTemplateFromVolumeOnPrimaryStorageMsg) msg);
        } else if (msg instanceof PrimaryStorageDeletionMsg) {
            handle((PrimaryStorageDeletionMsg) msg);
        } else if (msg instanceof DetachPrimaryStorageFromClusterMsg) {
            handle((DetachPrimaryStorageFromClusterMsg) msg);
        } else if (msg instanceof DownloadDataVolumeToPrimaryStorageMsg) {
            handleBase((DownloadDataVolumeToPrimaryStorageMsg) msg);
        } else if (msg instanceof DeleteBitsOnPrimaryStorageMsg) {
            handle((DeleteBitsOnPrimaryStorageMsg) msg);
        } else if (msg instanceof ConnectPrimaryStorageMsg) {
            handle((ConnectPrimaryStorageMsg) msg);
        } else if (msg instanceof DownloadIsoToPrimaryStorageMsg) {
            handleBase((DownloadIsoToPrimaryStorageMsg) msg);
	    } else {
	        bus.dealWithUnknownMessage(msg);
	    }
	}

    private void handleBase(DownloadIsoToPrimaryStorageMsg msg) {
        checkIfBackupStorageAttachedToMyZone(msg.getIsoSpec().getSelectedBackupStorage().getImageUuid());
        handle(msg);
    }

    private void handle(final ConnectPrimaryStorageMsg msg) {
        final ConnectPrimaryStorageReply reply = new ConnectPrimaryStorageReply();
        self.setStatus(PrimaryStorageStatus.Connecting);
        self = dbf.updateAndRefresh(self);
        connectHook(msg, new Completion(msg) {
            @Override
            public void success() {
                self.setStatus(PrimaryStorageStatus.Connected);
                self = dbf.updateAndRefresh(self);
                reply.setConnected(true);
                logger.debug(String.format("successfully connected primary storage[uuid:%s]", self.getUuid()));
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                self.setStatus(PrimaryStorageStatus.Disconnected);
                self = dbf.updateAndRefresh(self);
                logger.debug(String.format("failed to connect primary storage[uuid:%s], %s", self.getUuid(), errorCode));
                reply.setConnected(false);
                bus.reply(msg, reply);
            }
        });
    }

    private void handleBase(DownloadDataVolumeToPrimaryStorageMsg msg) {
        checkIfBackupStorageAttachedToMyZone(msg.getBackupStorageRef().getBackupStorageUuid());
        handle(msg);
    }

    @Transactional(readOnly = true)
    private void checkIfBackupStorageAttachedToMyZone(String bsUuid) {
        String sql = "select bs.uuid from BackupStorageVO bs, BackupStorageZoneRefVO ref where bs.uuid = ref.backupStorageUuid and ref.zoneUuid = :zoneUuid and bs.uuid = :bsUuid";
        TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
        q.setParameter("zoneUuid", self.getZoneUuid());
        q.setParameter("bsUuid", bsUuid);
        if (q.getResultList().isEmpty()) {
            throw new OperationFailureException(errf.stringToOperationError(
                    String.format("backup storage[uuid:%s] is not attached to zone[uuid:%s] the primary storage[uuid:%s] belongs to", bsUuid, self.getZoneUuid(), self.getUuid())
            ));
        }
    }

    private void handleBase(CreateTemplateFromVolumeOnPrimaryStorageMsg msg) {
        checkIfBackupStorageAttachedToMyZone(msg.getBackupStorageUuid());
        handle(msg);
    }

    private void handle(final DetachPrimaryStorageFromClusterMsg msg) {
        final DetachPrimaryStorageFromClusterReply reply = new DetachPrimaryStorageFromClusterReply();
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return getSyncId();
            }

            @Override
            public void run(final SyncTaskChain chain) {
                extpEmitter.beforeDetach(self, msg.getClusterUuid());
                detachHook(msg.getClusterUuid(), new Completion(msg, chain) {
                    @Override
                    public void success() {
                        SimpleQuery<PrimaryStorageClusterRefVO> q = dbf.createQuery(PrimaryStorageClusterRefVO.class);
                        q.add(PrimaryStorageClusterRefVO_.clusterUuid, Op.EQ, msg.getClusterUuid());
                        q.add(PrimaryStorageClusterRefVO_.primaryStorageUuid, Op.EQ, msg.getPrimaryStorageUuid());
                        List<PrimaryStorageClusterRefVO> refs = q.list();
                        dbf.removeCollection(refs, PrimaryStorageClusterRefVO.class);

                        self = dbf.reload(self);
                        extpEmitter.afterDetach(self, msg.getClusterUuid());

                        logger.debug(String.format("successfully detached primary storage[name: %s, uuid:%s]", self.getName(), self.getUuid()));
                        bus.reply(msg, reply);
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        extpEmitter.failToDetach(self, msg.getClusterUuid());
                        logger.warn(errorCode.toString());
                        reply.setError(errf.instantiateErrorCode(PrimaryStorageErrors.DETACH_ERROR, errorCode));
                        bus.reply(msg, reply);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("detach-primary-storage-%s-from-%s", self.getUuid(), msg.getClusterUuid());
            }
        });
    }

    private void handle(PrimaryStorageDeletionMsg msg) {
        PrimaryStorageInventory inv = PrimaryStorageInventory.valueOf(self);
        extpEmitter.beforeDelete(inv);
        deleteHook();
        extpEmitter.afterDelete(inv);

        PrimaryStorageDeletionReply reply = new PrimaryStorageDeletionReply();
        bus.reply(msg, reply);
    }

    @Transactional
    private void updateCapacity(long total, long avail) {
        PrimaryStorageCapacityVO cvo = dbf.getEntityManager().find(PrimaryStorageCapacityVO.class, self.getUuid(), LockModeType.PESSIMISTIC_WRITE);
        DebugUtils.Assert(cvo != null, String.format("how can there is no PrimaryStorageCapacityVO[uuid:%s]", self.getUuid()));

        cvo.setTotalCapacity(total);
        cvo.setAvailableCapacity(avail);
        dbf.getEntityManager().merge(cvo);
    }

    private void handle(PrimaryStorageReportCapacityMsg msg) {
        updateCapacity(msg.getTotalCapacity(), msg.getAvailableCapacity());
	    bus.reply(msg, new MessageReply());
    }

    protected void handleApiMessage(APIMessage msg) {
		if (msg instanceof APIDeletePrimaryStorageMsg) {
			handle((APIDeletePrimaryStorageMsg) msg);
		} else if (msg instanceof APIChangePrimaryStorageStateMsg) {
			handle((APIChangePrimaryStorageStateMsg) msg);
		} else if (msg instanceof APIAttachPrimaryStorageToClusterMsg) {
			handle((APIAttachPrimaryStorageToClusterMsg) msg);
		} else if (msg instanceof APIDetachPrimaryStorageFromClusterMsg) {
            handle((APIDetachPrimaryStorageFromClusterMsg) msg);
        } else if (msg instanceof APIReconnectPrimaryStorageMsg) {
            handle((APIReconnectPrimaryStorageMsg) msg);
		} else {
			bus.dealWithUnknownMessage(msg);
		}
	}

    private void handle(APIReconnectPrimaryStorageMsg msg) {
        APIReconnectPrimaryStorageEvent evt = new APIReconnectPrimaryStorageEvent(msg.getId());
        evt.setInventory(getSelfInventory());
        bus.publish(evt);
    }

    protected void handle(final APIDetachPrimaryStorageFromClusterMsg msg) {
        final APIDetachPrimaryStorageFromClusterEvent evt = new APIDetachPrimaryStorageFromClusterEvent(msg.getId());

        try {
            extpEmitter.preDetach(self, msg.getClusterUuid());
        } catch (PrimaryStorageException e) {
            evt.setErrorCode(errf.instantiateErrorCode(PrimaryStorageErrors.DETACH_ERROR, e.getMessage()));
            bus.publish(evt);
            return;
        }

        String issuer = PrimaryStorageVO.class.getSimpleName();
        List<PrimaryStorageDetachStruct> ctx = new ArrayList<PrimaryStorageDetachStruct>();
        PrimaryStorageDetachStruct struct = new PrimaryStorageDetachStruct();
        struct.setClusterUuid(msg.getClusterUuid());
        struct.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        ctx.add(struct);
        casf.asyncCascade(PrimaryStorageConstant.PRIMARY_STORAGE_DETACH_CODE, issuer, ctx, new Completion(msg) {
            @Override
            public void success() {
                self = dbf.reload(self);
                evt.setInventory(PrimaryStorageInventory.valueOf(self));
                bus.publish(evt);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                evt.setErrorCode(errorCode);
                bus.publish(evt);
            }
        });
	}

    protected void handle(final APIAttachPrimaryStorageToClusterMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return getSyncId();
            }

            @Override
            public void run(final SyncTaskChain chain) {
                attachCluster(msg, new NoErrorCompletion(msg, chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("attach-primary-storage-%s-to-cluster-%s", self.getUuid(), msg.getClusterUuid());
            }
        });

    }

    private void attachCluster(final APIAttachPrimaryStorageToClusterMsg msg, final NoErrorCompletion completion) {
        final APIAttachPrimaryStorageToClusterEvent evt = new APIAttachPrimaryStorageToClusterEvent(msg.getId());
        try {
            extpEmitter.preAttach(self, msg.getClusterUuid());
        } catch (PrimaryStorageException pe) {
            evt.setErrorCode(errf.instantiateErrorCode(PrimaryStorageErrors.ATTACH_ERROR, pe.getMessage()));
            bus.publish(evt);
            completion.done();
            return;
        }

        extpEmitter.beforeAttach(self, msg.getClusterUuid());
        attachHook(msg.getClusterUuid(), new Completion(msg, completion) {
            @Override
            public void success() {
                PrimaryStorageClusterRefVO ref = new PrimaryStorageClusterRefVO();
                ref.setClusterUuid(msg.getClusterUuid());
                ref.setPrimaryStorageUuid(self.getUuid());
                dbf.persist(ref);

                self = dbf.reload(self);
                extpEmitter.afterAttach(self, msg.getClusterUuid());

                PrimaryStorageInventory pinv = (PrimaryStorageInventory) invf.valueOf(self);
                evt.setInventory(pinv);
                logger.debug(String.format("successfully attached primary storage[name:%s, uuid:%s]", pinv.getName(), pinv.getUuid()));
                bus.publish(evt);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                extpEmitter.failToAttach(self, msg.getClusterUuid());
                evt.setErrorCode(errf.instantiateErrorCode(PrimaryStorageErrors.ATTACH_ERROR, errorCode));
                bus.publish(evt);
                completion.done();
            }
        });
    }

    protected void handle(APIChangePrimaryStorageStateMsg msg) {
        APIChangePrimaryStorageStateEvent evt = new APIChangePrimaryStorageStateEvent(msg.getId());

        PrimaryStorageState currState = self.getState();
        PrimaryStorageStateEvent event = PrimaryStorageStateEvent.valueOf(msg.getStateEvent());
        PrimaryStorageState nextState = AbstractPrimaryStorage.getNextState(currState, event);

        try {
            extpEmitter.preChange(self, event);
        } catch (PrimaryStorageException e) {
            evt.setErrorCode(errf.instantiateErrorCode(SysErrors.CHANGE_RESOURCE_STATE_ERROR, e.getMessage()));
            bus.publish(evt);
            return;
        }

        extpEmitter.beforeChange(self, event);
        changeStateHook(event, nextState);
        self.setState(nextState);
        self = dbf.updateAndRefresh(self);
        extpEmitter.afterChange(self, event, currState);
        evt.setInventory(PrimaryStorageInventory.valueOf(self));
        bus.publish(evt);
	}

	protected void handle(APIDeletePrimaryStorageMsg msg) {
        final APIDeletePrimaryStorageEvent evt = new APIDeletePrimaryStorageEvent(msg.getId());
        final String issuer = PrimaryStorageVO.class.getSimpleName();
        final List<PrimaryStorageInventory> ctx = PrimaryStorageInventory.valueOf(Arrays.asList(self));
        FlowChain chain = FlowChainBuilder.newSimpleFlowChain();
        chain.setName(String.format("delete-primary-storage-%s", msg.getUuid()));
        if (msg.getDeletionMode() == APIDeleteMessage.DeletionMode.Permissive) {
            chain.then(new NoRollbackFlow() {
                @Override
                public void run(final FlowTrigger trigger, Map data) {
                    casf.asyncCascade(CascadeConstant.DELETION_CHECK_CODE, issuer, ctx, new Completion(trigger) {
                        @Override
                        public void success() {
                            trigger.next();
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            trigger.fail(errorCode);
                        }
                    });
                }
            }).then(new NoRollbackFlow() {
                @Override
                public void run(final FlowTrigger trigger, Map data) {
                    casf.asyncCascade(CascadeConstant.DELETION_DELETE_CODE, issuer, ctx, new Completion(trigger) {
                        @Override
                        public void success() {
                            trigger.next();
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            trigger.fail(errorCode);
                        }
                    });
                }
            });
        } else {
            chain.then(new NoRollbackFlow() {
                @Override
                public void run(final FlowTrigger trigger, Map data) {
                    casf.asyncCascade(CascadeConstant.DELETION_FORCE_DELETE_CODE, issuer, ctx, new Completion(trigger) {
                        @Override
                        public void success() {
                            trigger.next();
                        }

                        @Override
                        public void fail(ErrorCode errorCode) {
                            trigger.fail(errorCode);
                        }
                    });
                }
            });
        }

        chain.done(new FlowDoneHandler(msg) {
            @Override
            public void handle(Map data) {
                casf.asyncCascadeFull(CascadeConstant.DELETION_CLEANUP_CODE, issuer, ctx, new NopeCompletion());
                bus.publish(evt);
            }
        }).error(new FlowErrorHandler(msg) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                evt.setErrorCode(errf.instantiateErrorCode(SysErrors.DELETE_RESOURCE_ERROR, errCode));
                bus.publish(evt);
            }
        }).start();
	}

}
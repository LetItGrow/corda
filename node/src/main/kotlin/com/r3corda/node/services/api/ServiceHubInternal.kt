package com.r3corda.node.services.api

import com.google.common.util.concurrent.ListenableFuture
import com.r3corda.core.crypto.Party
import com.r3corda.core.messaging.MessagingService
import com.r3corda.core.node.ServiceHub
import com.r3corda.core.node.services.TxWritableStorageService
import com.r3corda.core.protocols.ProtocolLogic
import com.r3corda.core.protocols.ProtocolLogicRefFactory
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.node.services.statemachine.ProtocolStateMachineImpl
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

interface MessagingServiceInternal : MessagingService {
    /**
     * Initiates shutdown: if called from a thread that isn't controlled by the executor passed to the constructor
     * then this will block until all in-flight messages have finished being handled and acknowledged. If called
     * from a thread that's a part of the [AffinityExecutor] given to the constructor, it returns immediately and
     * shutdown is asynchronous.
     */
    fun stop()
}

/**
 * This class lets you start up a [MessagingService]. Its purpose is to stop you from getting access to the methods
 * on the messaging service interface until you have successfully started up the system. One of these objects should
 * be the only way to obtain a reference to a [MessagingService]. Startup may be a slow process: some implementations
 * may let you cast the returned future to an object that lets you get status info.
 *
 * A specific implementation of the controller class will have extra features that let you customise it before starting
 * it up.
 */
interface MessagingServiceBuilder<out T : MessagingServiceInternal> {
    fun start(): ListenableFuture<out T>
}

private val log = LoggerFactory.getLogger(ServiceHubInternal::class.java)

abstract class ServiceHubInternal : ServiceHub {
    abstract val monitoringService: MonitoringService
    abstract val protocolLogicRefFactory: ProtocolLogicRefFactory
    abstract val schemaService: SchemaService

    abstract override val networkService: MessagingServiceInternal

    /**
     * Given a list of [SignedTransaction]s, writes them to the given storage for validated transactions and then
     * sends them to the vault for further processing. This is intended for implementations to call from
     * [recordTransactions].
     *
     * @param txs The transactions to record.
     */
    internal fun recordTransactionsInternal(writableStorageService: TxWritableStorageService, txs: Iterable<SignedTransaction>) {
        val stateMachineRunId = ProtocolStateMachineImpl.currentStateMachine()?.id
        if (stateMachineRunId != null) {
            txs.forEach {
                storageService.stateMachineRecordedTransactionMapping.addMapping(stateMachineRunId, it.id)
            }
        } else {
            log.warn("Transaction recorded from outside of a state machine")
        }
        txs.forEach { writableStorageService.validatedTransactions.addTransaction(it) }
        vaultService.notifyAll(txs.map { it.tx })
    }

    /**
     * TODO: borrowing this method from service manager work in another branch.  It's required to avoid circular dependency
     *       between SMM and the scheduler.  That particular problem should also be resolved by the service manager work
     *       itself, at which point this method would not be needed (by the scheduler).
     */
    abstract fun <T> startProtocol(logic: ProtocolLogic<T>): ListenableFuture<T>

    /**
     * Register the protocol factory we wish to use when a initiating party attempts to communicate with us. The
     * registration is done against a marker [KClass] which is sent in the session handshake by the other party. If this
     * marker class has been registered then the corresponding factory will be used to create the protocol which will
     * communicate with the other side. If there is no mapping then the session attempt is rejected.
     * @param markerClass The marker [KClass] present in a session initiation attempt, which is a 1:1 mapping to a [Class]
     * using the <pre>::class</pre> construct. Conventionally this is a [ProtocolLogic] subclass, however any class can
     * be used, with the default being the class of the initiating protocol. This enables the registration to be of the
     * form: registerProtocolInitiator(InitiatorProtocol::class, ::InitiatedProtocol)
     * @param protocolFactory The protocol factory generating the initiated protocol.
     */
    abstract fun registerProtocolInitiator(markerClass: KClass<*>, protocolFactory: (Party) -> ProtocolLogic<*>)

    /**
     * Return the protocol factory that has been registered with [markerClass], or null if no factory is found.
     */
    abstract fun getProtocolFactory(markerClass: Class<*>): ((Party) -> ProtocolLogic<*>)?

    override fun <T : Any> invokeProtocolAsync(logicType: Class<out ProtocolLogic<T>>, vararg args: Any?): ListenableFuture<T> {
        val logicRef = protocolLogicRefFactory.create(logicType, *args)
        @Suppress("UNCHECKED_CAST")
        val logic = protocolLogicRefFactory.toProtocolLogic(logicRef) as ProtocolLogic<T>
        return startProtocol(logic)
    }
}

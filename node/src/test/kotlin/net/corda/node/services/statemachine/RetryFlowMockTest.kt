package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.internal.packageName
import net.corda.core.messaging.MessageRecipients
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.internal.StartedNode
import net.corda.node.services.messaging.Message
import net.corda.node.services.persistence.DBTransactionStorage
import net.corda.nodeapi.internal.persistence.contextTransaction
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.MessagingServiceSpy
import net.corda.testing.node.internal.newContext
import net.corda.testing.node.internal.setMessagingServiceSpy
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.SQLException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RetryFlowMockTest {
    private lateinit var mockNet: InternalMockNetwork
    private lateinit var nodeA: StartedNode<MockNode>
    private lateinit var nodeB: StartedNode<MockNode>

    @Before
    fun start() {
        mockNet = InternalMockNetwork(threadPerNode = true, cordappPackages = listOf(this.javaClass.packageName))
        nodeA = mockNet.createNode()
        nodeB = mockNet.createNode()
        mockNet.startNodes()
        RetryFlow.count = 0
        SendAndRetryFlow.count = 0
        RetryInsertFlow.count = 0
    }

    private fun <T> StartedNode<MockNode>.startFlow(logic: FlowLogic<T>): CordaFuture<T> {
        return this.services.startFlow(logic, this.services.newContext()).getOrThrow().resultFuture
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `Single retry`() {
        assertEquals(Unit, nodeA.startFlow(RetryFlow(1)).get())
        assertEquals(2, RetryFlow.count)
    }

    @Test
    fun `Retry forever`() {
        assertThatThrownBy {
            nodeA.startFlow(RetryFlow(Int.MAX_VALUE)).getOrThrow()
        }.isInstanceOf(LimitedRetryCausingError::class.java)
        assertEquals(5, RetryFlow.count)
    }

    @Test
    fun `Retry does not set senderUUID`() {
        val messagesSent = mutableListOf<Message>()
        val partyB = nodeB.info.legalIdentities.first()
        nodeA.setMessagingServiceSpy(object : MessagingServiceSpy(nodeA.network) {
            override fun send(message: Message, target: MessageRecipients, retryId: Long?, sequenceKey: Any) {
                messagesSent.add(message)
                messagingService.send(message, target, retryId)
            }
        })
        nodeA.startFlow(SendAndRetryFlow(1, partyB)).get()
        assertNotNull(messagesSent.first().senderUUID)
        assertNull(messagesSent.last().senderUUID)
        assertEquals(2, SendAndRetryFlow.count)
    }

    @Test
    fun `Retry duplicate insert`() {
        assertEquals(Unit, nodeA.startFlow(RetryInsertFlow(1)).get())
        assertEquals(2, RetryInsertFlow.count)
    }

    @Test
    fun `Patient records do not leak in hospital`() {
        assertEquals(Unit, nodeA.startFlow(RetryFlow(1)).get())
        assertEquals(0, StaffedFlowHospital.patients.size)
        assertEquals(2, RetryFlow.count)
    }
}

class LimitedRetryCausingError : ConstraintViolationException("Test message", SQLException(), "Test constraint")

class RetryCausingError : SQLException("deadlock")

class RetryFlow(private val i: Int) : FlowLogic<Unit>() {
    companion object {
        var count = 0
    }

    @Suspendable
    override fun call() {
        logger.info("Hello $count")
        if (count++ < i) {
            if (i == Int.MAX_VALUE) {
                throw LimitedRetryCausingError()
            } else {
                throw RetryCausingError()
            }
        }
    }
}

@InitiatingFlow
class SendAndRetryFlow(private val i: Int, private val other: Party) : FlowLogic<Unit>() {
    companion object {
        var count = 0
    }

    @Suspendable
    override fun call() {
        logger.info("Sending...")
        val session = initiateFlow(other)
        session.send("Boo")
        if (count++ < i) {
            throw RetryCausingError()
        }
    }
}

@Suppress("unused")
@InitiatedBy(SendAndRetryFlow::class)
class ReceiveFlow2(private val other: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val received = other.receive<String>().unwrap { it }
        logger.info("Received... $received")
    }
}

class RetryInsertFlow(private val i: Int) : FlowLogic<Unit>() {
    companion object {
        var count = 0
    }

    @Suspendable
    override fun call() {
        logger.info("Hello")
        doInsert()
        // Checkpoint so we roll back to here
        FlowLogic.sleep(Duration.ofSeconds(0))
        if (count++ < i) {
            doInsert()
        }
    }

    private fun doInsert() {
        val tx = DBTransactionStorage.DBTransaction("Foo")
        contextTransaction.session.save(tx)
    }
}

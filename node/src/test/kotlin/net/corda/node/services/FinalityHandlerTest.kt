package net.corda.node.services

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.internal.StartedNode
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.node.services.statemachine.StaffedFlowHospital.MedicalHistory.Record.Observation
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class FinalityHandlerTest {

    @Test
    fun `blah blah`() {
        val mockNet = InternalMockNetwork(cordappPackages = emptyList())
        val alice = mockNet.createNode(InternalMockNodeParameters(
                legalName = ALICE_NAME,
                extraCordappPackages = listOf("net.corda.finance.contracts.asset")
        ))
        var bob = mockNet.createNode(InternalMockNodeParameters(legalName = BOB_NAME, extraCordappPackages = listOf("net.corda.finance.contracts.asset")))

        val stx = TransactionBuilder(mockNet.defaultNotaryIdentity).let {
            Cash().generateIssue(
                    it,
                    1000.POUNDS.issuedBy(alice.info.singleIdentity().ref(0)),
                    bob.info.singleIdentity(),
                    mockNet.defaultNotaryIdentity
            )
            alice.services.signInitialTransaction(it)
        }

        val flow = alice.services.startFlow(FinalityFlow(stx))
        mockNet.runNetwork()
        val finalisedTx = flow.resultFuture.getOrThrow()

        // Confirm that the flow for bob failed and it was sent to the flow hospital
        assertThat(bob.getTransaction(finalisedTx.id)).isNull()
        val medicalHistories = StaffedFlowHospital.patients.values
        assertThat(medicalHistories).hasSize(1)
        val observations = medicalHistories.first().records.filterIsInstance<Observation>()
        assertThat(observations).hasSize(1)
        assertThat(observations[0].by).contains(StaffedFlowHospital.FinalityDoctor)
        assertThat(observations[0].errors[0]).isInstanceOf(TransactionVerificationException.ContractConstraintRejection::class.java)

//        bob.internals.disableDBCloseOnStop()
//        bob.internals.stop()
//        bob = mockNet.createNode(InternalMockNodeParameters(legalName = BOB_NAME, forcedID = bob.internals.id, extraCordappPackages = listOf("net.corda.finance.contracts.asset")))
//        bob.internals.acceptableLiveFiberCountOnStop = 1
//        bob.internals.manuallyCloseDB()
//        assertThat(bob.getTransaction(finalisedTx.id)).isEqualTo(finalisedTx)
    }

    private fun StartedNode<InternalMockNetwork.MockNode>.getTransaction(id: SecureHash): SignedTransaction? {
        return database.transaction {
            services.validatedTransactions.getTransaction(id)
        }
    }
}

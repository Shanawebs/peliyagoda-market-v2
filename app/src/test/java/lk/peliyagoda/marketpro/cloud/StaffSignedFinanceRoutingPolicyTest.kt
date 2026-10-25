package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.*
import org.junit.Test

class StaffSignedFinanceRoutingPolicyTest {
    @Test fun allAndOnlyFiveFinanceTypesRouteToSignedServerEndpoints() {
        val p=StaffSignedFinanceRoutingPolicy
        assertEquals(setOf("purchase","requisition","purchase_addition",
            "daily_cash_received","daily_other_expenses"),p.allTypes())
        assertEquals(StaffSignedFinanceRoutingPolicy.Channel.PURCHASE_V2,
            p.channel("purchase"))
        for (type in p.allTypes()-"purchase") {
            assertEquals(type,StaffSignedFinanceRoutingPolicy.Channel.FINANCIAL_V3,
                p.channel(type))
        }
        for (type in listOf("item","category","customer","owner",
            "purchase_delete","", "Purchase")) {
            assertThrows(IllegalArgumentException::class.java) {
                p.channel(type)
            }
        }
    }

    private fun gates()=BooleanArray(10){true}.apply {this[0]=false}
    private fun ready(v:BooleanArray)=StaffSignedFinanceRoutingPolicy.ready(
        preview=v[0],staffLogin=v[1],staffPull=v[2],
        pairing=v[3],ownerMfa=v[4],keyEnrollment=v[5],
        writerApproval=v[6],financeRpc=v[7],
        serverProof=v[8],autoUpload=v[9])
    @Test fun anyMissingMfaKeyDeviceServerProofOrAutoFlagFailsClosed() {
        assertTrue(ready(gates()))
        for(i in 0..9) {
            val flags=gates()
            flags[i]=if(i==0) true else false
            assertFalse("Missing gate $i unlocked Staff finance worker",ready(flags))
        }
    }
}

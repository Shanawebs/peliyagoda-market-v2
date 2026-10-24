package lk.peliyagoda.marketpro.cloud

import lk.peliyagoda.marketpro.security.StaffDeviceChallenge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffSignedReadPolicyTest {
    private val w="11111111-1111-4111-8111-111111111111"
    private val u="33333333-3333-4333-8333-333333333333"
    private val d="f1111111-1111-4111-8111-111111111111"
    private val sid="91111111-1111-4111-8111-111111111111"
    private val fp="a".repeat(64)
    private val now=1800000000000L
    private val ses=TeamPullSession("A".repeat(100),u,d,w)
    private val challenge=StaffDeviceChallenge(
        w,u,d,sid,"c1111111-1111-4111-8111-111111111111",
        "a".repeat(64),"read",now-1000,now+59000
    )
    private fun expectDenied(c:StaffDeviceChallenge=challenge,
        s:TeamPullSession=ses,user:String=u,login:String=sid,
        physical:String=d,serverFp:String=fp,phoneFp:String=fp,
        current:Long=now) {
        var denied=false
        try { StaffSignedReadPolicy.checkChallenge(
            c,s,user,login,physical,serverFp,phoneFp,current) }
        catch (_:IllegalArgumentException){denied=true}
        assertTrue("Unverified Staff page proof accepted",denied)
    }
    @Test fun exactCurrentStaffReadPhoneCanSign() {
        StaffSignedReadPolicy.checkChallenge(
            challenge,ses,u,sid,d,fp,fp,now)
    }
    @Test fun stolenBearerOrReplacedPhoneCannotSign() {
        expectDenied(user="22222222-2222-4222-8222-222222222222")
        expectDenied(login="92222222-2222-4222-8222-222222222222")
        expectDenied(physical="d1111111-1111-4111-8111-111111111111")
        expectDenied(c=challenge.copy(workspaceId="a1111111-1111-4111-8111-111111111111"))
        expectDenied(serverFp="b".repeat(64))
        expectDenied(phoneFp="b".repeat(64))
    }
    @Test fun noWriteProofReplayAndExpiredNonce() {
        expectDenied(c=challenge.copy(purpose="write"))
        expectDenied(c=challenge.copy(purpose="pair"))
        expectDenied(c=challenge.copy(nonceHex="wrong"))
        expectDenied(current=challenge.expiresAtMillis)
    }
    @Test fun missingReadGateFailsClosed() {
        assertFalse(StaffSignedReadPolicy.ready(true,true,true,true))
        assertFalse(StaffSignedReadPolicy.ready(false,true,true,false))
        assertFalse(StaffSignedReadPolicy.ready(false,false,true,true))
        assertFalse(StaffSignedReadPolicy.ready(false,true,false,true))
        assertTrue(StaffSignedReadPolicy.ready(false,true,true,true))
    }
}

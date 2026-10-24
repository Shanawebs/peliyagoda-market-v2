package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamPullRequestPolicyTest {
    private val workspace = "e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private val device = "d134c7de-8c31-4808-badc-24617d35fdcc"
    private val user = "bf49c22f-f245-4d3c-987e-c5ec081d7cad"
    private val token = "placeholder-not-a-real-token-please-dont-send"
    private fun session()=TeamPullSession(token,user,device,workspace)
    private fun page()=TeamPullPageRequest(
        workspace,device,"purchase",null,null,35
    )
    private fun validate(s:TeamPullSession=session(),p:TeamPullPageRequest=page(),
                         physical:String=device) =
        TeamPullRequestPolicy.validate(s,p,physical)

    @Test fun offlinePreviewAndUnreleasedServerAlwaysDisablePull() {
        assertFalse(TeamPullReadiness.ready(true,true,true,true))
        assertFalse(TeamPullReadiness.ready(false,false,true,true))
        assertFalse(TeamPullReadiness.ready(false,true,false,true))
        assertFalse(TeamPullReadiness.ready(false,true,true,false))
        assertTrue(TeamPullReadiness.ready(false,true,true,true))
    }

    @Test fun validServerBoundSessionAndCursorEnvelopeAccepted() {
        validate()
        validate(p=page().copy(
            afterUpdatedAt="2026-10-24T08:12:10.000+00:00",
            afterCloudId="f1111111-1111-4111-8111-111111111111",
            pageSize=50
        ))
    }

    @Test fun categoriesArePulledBeforeFinancialRows() {
        assertEquals(
            listOf("category","item","requisition","purchase",
                "purchase_addition","daily_cash_received"),
            TeamPullRequestPolicy.orderedTypes
        )
        assertFalse("invoice_photo" in TeamPullRequestPolicy.orderedTypes)
        assertFalse("sync_operations" in TeamPullRequestPolicy.orderedTypes)
        assertFalse("daily_other_expenses" in TeamPullRequestPolicy.orderedTypes)
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyMismatchedPhysicalDeviceId() {
        validate(physical="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyWrongWorkspace() {
        validate(p=page().copy(workspaceId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denySpoofedDeviceInRequest() {
        validate(p=page().copy(deviceId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyMissingToken() {
        validate(s=session().copy(accessToken=""))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyMissingUserIdentity() {
        validate(s=session().copy(authenticatedUserId=""))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyInternalTableRequest() {
        validate(p=page().copy(entityType="registered_devices"))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyUnboundedPage() {
        validate(p=page().copy(pageSize=101))
    }

    @Test(expected=IllegalArgumentException::class)
    fun denyIncompleteCursor() {
        validate(p=page().copy(afterUpdatedAt="2026-10-24T08:00:00Z"))
    }

    @Test(expected=java.time.format.DateTimeParseException::class)
    fun denyInvalidTimestampCursor() {
        validate(p=page().copy(
            afterUpdatedAt="saturday",
            afterCloudId="f1111111-1111-4111-8111-111111111111"
        ))
    }
}

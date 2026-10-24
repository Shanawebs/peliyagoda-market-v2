package lk.peliyagoda.marketpro.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogReviewNetworkPolicyTest {
    private val workspace="e46b90c5-ed6a-4952-82e9-8582fac06d89"
    private val device="b1111111-1111-4111-8111-111111111111"
    private val user="33333333-3333-4333-8333-333333333333"
    private val owner="22222222-2222-4222-8222-222222222222"
    private val token="temporary-test-JWT-token-which-should-never-be-logged"

    private fun staff()=CatalogReviewNetworkSession(
        token,user,device,workspace,CatalogReviewActorRole.STAFF)
    private fun ownerSession()=staff().copy(
        userUuid=owner,role=CatalogReviewActorRole.OWNER)

    @Test fun offlineOrAnyUnreleasedGateAlwaysBlocksNetwork() {
        assertFalse(CatalogReviewNetworkPolicy.canEnable(true,true,true,true))
        assertFalse(CatalogReviewNetworkPolicy.canEnable(false,false,true,true))
        assertFalse(CatalogReviewNetworkPolicy.canEnable(false,true,false,true))
        assertFalse(CatalogReviewNetworkPolicy.canEnable(false,true,true,false))
        assertTrue(CatalogReviewNetworkPolicy.canEnable(false,true,true,true))
    }

    @Test fun staffCanOnlySubmitEvidenceNotFetchOrApprove() {
        CatalogReviewNetworkPolicy.validate(staff(),device,workspace,
            CatalogReviewAction.STAFF_SUBMIT)
        assertDenied {
            CatalogReviewNetworkPolicy.validate(staff(),device,workspace,
                CatalogReviewAction.OWNER_LIST)
        }
        assertDenied {
            CatalogReviewNetworkPolicy.validate(staff(),device,workspace,
                CatalogReviewAction.OWNER_APPROVE)
        }
    }

    @Test fun ownerCanReadAndApproveButCannotPretendToBeStaff() {
        CatalogReviewNetworkPolicy.validate(ownerSession(),device,workspace,
            CatalogReviewAction.OWNER_LIST)
        CatalogReviewNetworkPolicy.validate(ownerSession(),device,workspace,
            CatalogReviewAction.OWNER_APPROVE)
        assertDenied {
            CatalogReviewNetworkPolicy.validate(ownerSession(),device,workspace,
                CatalogReviewAction.STAFF_SUBMIT)
        }
    }

    @Test fun mismatchedWorkspaceAndPhysicalPhoneAreDenied() {
        assertDenied {
            CatalogReviewNetworkPolicy.validate(staff(),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",workspace,
                CatalogReviewAction.STAFF_SUBMIT)
        }
        assertDenied {
            CatalogReviewNetworkPolicy.validate(staff(),device,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                CatalogReviewAction.STAFF_SUBMIT)
        }
    }

    @Test fun bearerTokenNeverPrintedByKotlinDataClass() {
        assertFalse(staff().toString().contains(token))
        assertTrue(staff().toString().contains("[REDACTED]"))
    }

    @Test fun newlineHeaderInjectionBlockedBeforeHttpCall() {
        assertDenied {
            CatalogReviewNetworkPolicy.validate(
                staff().copy(token=token+"\nX-Forged: 1"),device,workspace,
                CatalogReviewAction.STAFF_SUBMIT)
        }
    }

    @Test fun paginationMustBeStrictlyBoundedWithValidUuidCursor() {
        CatalogReviewNetworkPolicy.validCursor(null)
        CatalogReviewNetworkPolicy.validCursor(
            "a1111111-1111-4111-8111-111111111111")
        CatalogReviewNetworkPolicy.validPageSize(1)
        CatalogReviewNetworkPolicy.validPageSize(50)
        assertDenied { CatalogReviewNetworkPolicy.validPageSize(0) }
        assertDenied { CatalogReviewNetworkPolicy.validPageSize(51) }
        assertDenied { CatalogReviewNetworkPolicy.validCursor("0 OR 1=1") }
    }

    private fun assertDenied(block:()->Unit) {
        try {
            block()
            throw AssertionError("Security policy did not reject an unsafe request")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

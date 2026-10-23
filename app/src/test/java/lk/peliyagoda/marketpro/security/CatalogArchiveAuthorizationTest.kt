package lk.peliyagoda.marketpro.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogArchiveAuthorizationTest {
    @Test
    fun approvedSixDigitCodeIsAccepted() {
        assertTrue(CatalogArchiveAuthorization.valid("160530"))
    }

    @Test
    fun wrongCodeIsRejected() {
        assertFalse(CatalogArchiveAuthorization.valid("160531"))
        assertFalse(CatalogArchiveAuthorization.valid("000000"))
    }

    @Test
    fun malformedCodesAreRejected() {
        assertFalse(CatalogArchiveAuthorization.valid(""))
        assertFalse(CatalogArchiveAuthorization.valid("16053"))
        assertFalse(CatalogArchiveAuthorization.valid("1605300"))
        assertFalse(CatalogArchiveAuthorization.valid(" 160530"))
        assertFalse(CatalogArchiveAuthorization.valid("16a530"))
    }
}

package mattecarra.accapp

import mattecarra.accapp.utils.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    private fun newer(a: String, b: String) = VersionCompare.compare(a, b) > 0
    private fun older(a: String, b: String) = VersionCompare.compare(a, b) < 0
    private fun same(a: String, b: String) = VersionCompare.compare(a, b) == 0

    @Test
    fun plainNumericOrdering() {
        assertTrue(newer("2.0.1", "2.0.0"))
        assertTrue(older("2.0.0", "2.0.1"))
        assertTrue(same("2.0.0", "2.0.0"))
        assertTrue(newer("2.1.0", "2.0.9"))
        assertTrue(newer("3.0.0", "2.9.9"))
    }

    @Test
    fun differingSegmentCountsPadWithZero() {
        assertTrue(same("2.0", "2.0.0"))
        assertTrue(same("2", "2.0.0"))
        assertTrue(newer("2.1", "2.0.9"))
        assertTrue(older("2.0", "2.0.1"))
    }

    @Test
    fun stableIsNeverOfferedAsDowngradeToAnRcTester() {
        assertFalse(newer("2.0.0", "2.0.1-rc1"))
        assertTrue(older("2.0.0", "2.0.1-rc1"))
    }

    @Test
    fun rcIsNewerThanThePrecedingStable() {
        assertTrue(newer("2.0.1-rc1", "2.0.0"))
        assertTrue(newer("2.0.1-rc1", "1.9.9"))
    }

    @Test
    fun sameRcIsNotAnUpdate() {
        assertTrue(same("2.0.1-rc1", "2.0.1-rc1"))
        assertTrue(same("v2.0.1-rc1", "2.0.1-rc1"))
    }

    @Test
    fun finalReleaseOutranksItsPrerelease() {
        assertTrue(newer("2.0.1", "2.0.1-rc1"))
        assertTrue(older("2.0.1-rc1", "2.0.1"))
    }

    @Test
    fun rcProgressionUsesNaturalNumberOrder() {
        assertTrue(newer("2.0.1-rc2", "2.0.1-rc1"))
        assertTrue(newer("2.0.1-rc10", "2.0.1-rc2"))
        assertTrue(newer("2.0.1-rc10", "2.0.1-rc9"))
        assertTrue(older("2.0.1-beta1", "2.0.1-rc1"))
    }

    @Test
    fun toleratesVPrefixAndWhitespace() {
        assertTrue(newer("v2.0.1", "2.0.0"))
        assertTrue(newer("V2.0.1", "v2.0.0"))
        assertTrue(same(" 2.0.1 ", "2.0.1"))
        assertTrue(same("v2.0.1", "2.0.1"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertTrue(same("2.0.1+build.5", "2.0.1"))
        assertTrue(newer("2.0.2+abc", "2.0.1+def"))
    }

    @Test
    fun blankAndGarbageAreSafe() {
        assertTrue(same("", ""))
        assertFalse(newer("", "2.0.0"))
        assertFalse(newer("garbage", "2.0.0"))
        assertFalse(newer("2.0.0", "2.0.0"))
    }

    @Test
    fun comparatorIsAntisymmetric() {
        val pairs = listOf(
            "2.0.0" to "2.0.1",
            "2.0.1-rc1" to "2.0.0",
            "2.0.1-rc1" to "2.0.1",
            "2.0.1-rc2" to "2.0.1-rc1",
            "v2.0.1" to "2.0.1"
        )
        for ((a, b) in pairs) {
            val ab = VersionCompare.compare(a, b)
            val ba = VersionCompare.compare(b, a)
            assertEquals("antisymmetry for $a vs $b", Integer.signum(ab), -Integer.signum(ba))
        }
    }
}

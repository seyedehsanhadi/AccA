package mattecarra.accapp

import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.utils.ExportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules a user asked for by name: an export must never overwrite an earlier one, and a
 * capacity limit must not disappear from the screen just because it is set to 100.
 *
 * Both are pure logic, so they belong here rather than in a device run. The device proved the
 * behaviour once; these keep it proven.
 */
class ExportImportRulesTest {

    // ---- export filenames --------------------------------------------------------------------

    @Test
    fun `a free name is used as is`() {
        assertEquals("acca-scripts-12.json", ExportStore.uniqueName("acca-scripts-12.json", emptySet()))
    }

    @Test
    fun `a taken name gets a suffix rather than replacing the file`() {
        val taken = setOf("acca-scripts-12.json")
        assertEquals("acca-scripts-12-2.json", ExportStore.uniqueName("acca-scripts-12.json", taken))
    }

    @Test
    fun `repeated exports keep counting up and never collide`() {
        // The exact sequence the device produced: three saves after an existing file.
        var taken = setOf("acca-scripts-12.json")
        val produced = mutableListOf<String>()
        repeat(3) {
            val n = ExportStore.uniqueName("acca-scripts-12.json", taken)
            produced.add(n)
            taken = taken + n
        }
        assertEquals(listOf("acca-scripts-12-2.json", "acca-scripts-12-3.json", "acca-scripts-12-4.json"), produced)
        // and nothing in the sequence repeats, which is the property that matters
        assertEquals(produced.size, produced.toSet().size)
    }

    @Test
    fun `a gap in the sequence is filled rather than skipped past`() {
        val taken = setOf("acca-scripts-12.json", "acca-scripts-12-3.json")
        assertEquals("acca-scripts-12-2.json", ExportStore.uniqueName("acca-scripts-12.json", taken))
    }

    // ---- export filename sanitising ----------------------------------------------------------
    // A name is interpolated into a single-quoted shell command, so a quote in a script name must
    // never survive into it.

    @Test
    fun `a quote in a script name cannot reach the shell`() {
        val out = ExportStore.sanitize("Bob's script.json")
        assertFalse(out.contains("'"))
        assertFalse(out.contains(" "))
    }

    @Test
    fun `a shell metacharacter is stripped`() {
        val out = ExportStore.sanitize("evil; rm -rf /.json")
        assertFalse(out.contains(";"))
        assertFalse(out.contains("/"))
    }

    @Test
    fun `a name that sanitises to nothing still produces a usable filename`() {
        assertEquals("acca-export", ExportStore.sanitize("///"))
        assertEquals("acca-export", ExportStore.sanitize(""))
    }

    @Test
    fun `an ordinary generated name passes through untouched`() {
        assertEquals("acca-scripts-12.json", ExportStore.sanitize("acca-scripts-12.json"))
    }

    @Test
    fun `a very long name is truncated rather than rejected`() {
        val out = ExportStore.sanitize("x".repeat(300) + ".json")
        assertTrue(out.length <= 64)
        assertTrue(out.isNotEmpty())
    }

    // ---- capacity control: 100 is a setting, not an off switch --------------------------------

    @Test
    fun `pause 100 with a real resume level is still an active limit`() {
        // Reported: setting the pause level to 100 made the whole capacity row vanish from the
        // dashboard, even though shutdown and resume were still being enforced.
        val c = AccConfig.ConfigCapacity(shutdown = 5, resume = 70, pause = 100)
        assertTrue(c.isEnabled)
    }

    @Test
    fun `the disabled sentinel is the pair, and disable writes both halves`() {
        val c = AccConfig.ConfigCapacity(shutdown = 5, resume = 70, pause = 75)
        assertTrue(c.isEnabled)
        c.disable()
        assertEquals(100, c.pause)
        assertEquals(99, c.resume)
        assertFalse(c.isEnabled)
    }

    @Test
    fun `an ordinary percent limit is enabled`() {
        assertTrue(AccConfig.ConfigCapacity(5, 70, 75).isEnabled)
        assertTrue(AccConfig.ConfigCapacity(5, 40, 60).isEnabled)
    }

    @Test
    fun `a millivolt limit is enabled`() {
        // ACC's second domain: 3001..5000 is mV, not percent.
        assertTrue(AccConfig.ConfigCapacity(3300, 3900, 4100).isEnabled)
    }

    @Test
    fun `a pause outside both domains reads as disabled`() {
        assertFalse(AccConfig.ConfigCapacity(5, 70, 0).isEnabled)
        assertFalse(AccConfig.ConfigCapacity(5, 70, 2000).isEnabled)
        assertFalse(AccConfig.ConfigCapacity(5, 70, 6000).isEnabled)
    }

    @Test
    fun `the disabled pair does not accidentally catch a neighbouring setting`() {
        // 100/98 is a 2% band a user could plausibly pick, and it must NOT read as disabled -
        // only the exact pair disable() writes does.
        assertTrue(AccConfig.ConfigCapacity(5, 98, 100).isEnabled)
        // 99/100 IS the sentinel, and a 1% band enforces nothing anyway, so reading it as
        // disabled is honest rather than a false negative.
        assertFalse(AccConfig.ConfigCapacity(5, 99, 100).isEnabled)
    }
}

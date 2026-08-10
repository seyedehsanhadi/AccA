package mattecarra.accapp

import mattecarra.accapp.models.AccaScript
import mattecarra.accapp.models.ScriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Script export selection.
 *
 * Field report: "Deselected all, checked two scripts, only one got exported. Went back and exported
 * the other, and now one of the two has the other also concatenated in it."
 *
 * The exporter serialises `getCheckedEntries().map { it.script }`, which filters the MODEL. Anything
 * that lets the visible tick disagree with the model produces exactly that pair of symptoms: a
 * ticked row missing from the export, and an unticked row still riding along in the next one. These
 * tests pin the model half of that contract, which is where the fix lives and where a regression
 * would silently return.
 *
 * Pure JVM: no Android, no device, no root gate. Runs in CI.
 */
class ScriptExportSelectionTest {

    private var nextUid = 1
    private fun entry(name: String): ScriptEntry =
        ScriptEntry(AccaScript(nextUid++, name, "desc $name", "echo $name", "", 0, 0))

    /** The exporter's real selector. */
    private fun checkedOf(entries: List<ScriptEntry>) = entries.filter { it.isChecked() }

    // ---- the reported bug ----------------------------------------------------------------------

    @Test
    fun `two ticked scripts both reach the exporter`() {
        val all = listOf(entry("AAA"), entry("BBB"), entry("CCC"))
        all.forEach { it.setIsChecked(false) }

        all[0].setIsChecked(true)
        all[1].setIsChecked(true)

        val checked = checkedOf(all)
        assertEquals("both ticked scripts must be exported, not one", 2, checked.size)
        assertEquals(listOf("AAA", "BBB"), checked.map { it.script.scName })
    }

    @Test
    fun `an unticked script never rides along in a later export`() {
        val all = listOf(entry("AAA"), entry("BBB"))
        all.forEach { it.setIsChecked(true) }

        // export one, then come back and export only the other
        all[0].setIsChecked(false)

        val checked = checkedOf(all)
        assertEquals("only the still-ticked script may be exported", 1, checked.size)
        assertEquals("BBB", checked[0].script.scName)
    }

    // ---- toggling must round-trip, not latch ---------------------------------------------------

    @Test
    fun `toggle flips and flips back`() {
        val e = entry("AAA")
        val start = e.isChecked()
        e.setIsChecked(!start)
        assertEquals(!start, e.isChecked())
        e.setIsChecked(!e.isChecked())
        assertEquals(start, e.isChecked())
    }

    @Test
    fun `setting the same value twice is stable`() {
        val e = entry("AAA")
        e.setIsChecked(true); e.setIsChecked(true)
        assertTrue(e.isChecked())
        e.setIsChecked(false); e.setIsChecked(false)
        assertFalse(e.isChecked())
    }

    // ---- bulk operations, the Select all / Deselect all the user asked for ----------------------

    @Test
    fun `deselect all clears every entry`() {
        val all = List(12) { entry("S$it") }
        all.forEach { it.setIsChecked(true) }

        all.forEach { if (it.isChecked()) it.setIsChecked(false) }   // uncheckAll()

        assertEquals("deselect all must leave nothing selected", 0, checkedOf(all).size)
    }

    @Test
    fun `select all ticks every entry`() {
        val all = List(12) { entry("S$it") }
        all.forEach { it.setIsChecked(false) }

        all.forEach { if (!it.isChecked()) it.setIsChecked(true) }   // checkAll()

        assertEquals(12, checkedOf(all).size)
    }

    @Test
    fun `deselect all then tick two exports exactly those two`() {
        val all = List(12) { entry("S$it") }
        all.forEach { it.setIsChecked(true) }
        all.forEach { if (it.isChecked()) it.setIsChecked(false) }

        all[3].setIsChecked(true)
        all[7].setIsChecked(true)

        val checked = checkedOf(all)
        assertEquals(2, checked.size)
        assertEquals(listOf("S3", "S7"), checked.map { it.script.scName })
    }

    // ---- listener wiring: the other route to view/model drift -----------------------------------

    @Test
    fun `listener sees every change`() {
        val e = entry("AAA")
        val seen = mutableListOf<Boolean>()
        e.setOnCheckedChangedListener(object : ScriptEntry.Listener {
            override fun onCheckChanged(value: Boolean) { seen.add(value) }
        })
        e.setIsChecked(true)
        e.setIsChecked(false)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `a detached listener stops receiving, so a recycled row cannot drive another`() {
        val e = entry("AAA")
        val seen = mutableListOf<Boolean>()
        val l = object : ScriptEntry.Listener {
            override fun onCheckChanged(value: Boolean) { seen.add(value) }
        }
        e.setOnCheckedChangedListener(l)
        e.setIsChecked(true)

        // what onViewRecycled does
        e.setOnCheckedChangedListener(null)
        e.setIsChecked(false)

        assertEquals("a recycled holder must not keep receiving updates", listOf(true), seen)
        assertFalse("the model still changes even with no listener", e.isChecked())
    }

    @Test
    fun `rebinding a listener does not disturb the checked state`() {
        val e = entry("AAA")
        e.setIsChecked(true)
        e.setOnCheckedChangedListener(object : ScriptEntry.Listener {
            override fun onCheckChanged(value: Boolean) {}
        })
        assertTrue("binding a view must never change the selection", e.isChecked())
        e.setOnCheckedChangedListener(null)
        assertTrue(e.isChecked())
    }

    // ---- entries must stay independent ----------------------------------------------------------

    @Test
    fun `ticking one entry does not tick its neighbours`() {
        val all = List(5) { entry("S$it") }
        all.forEach { it.setIsChecked(false) }

        all[2].setIsChecked(true)

        assertEquals(1, checkedOf(all).size)
        assertEquals("S2", checkedOf(all)[0].script.scName)
    }

    @Test
    fun `entries with identical names stay separately selectable`() {
        val a = entry("same"); val b = entry("same")
        a.setIsChecked(false); b.setIsChecked(false)
        a.setIsChecked(true)
        assertTrue(a.isChecked())
        assertFalse("a duplicate name must not alias another row's selection", b.isChecked())
    }

    // ---- what the exporter actually serialises ---------------------------------------------------

    @Test
    fun `exporter carries the script bodies of exactly the ticked rows`() {
        val all = listOf(entry("AAA"), entry("BBB"), entry("CCC"))
        all.forEach { it.setIsChecked(false) }
        all[0].setIsChecked(true)
        all[2].setIsChecked(true)

        val payload = checkedOf(all).map { it.script }
        assertEquals(2, payload.size)
        assertEquals(listOf("echo AAA", "echo CCC"), payload.map { it.scBody })
    }

    @Test
    fun `nothing ticked yields an empty payload rather than everything`() {
        val all = List(4) { entry("S$it") }
        all.forEach { it.setIsChecked(false) }
        assertEquals("an empty selection must export nothing at all", 0, checkedOf(all).size)
    }
}

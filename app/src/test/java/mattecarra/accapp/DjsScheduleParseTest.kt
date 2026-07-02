package mattecarra.accapp

import mattecarra.accapp.djs.v202107280.DjsHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjsScheduleParseTest {

    private val h = DjsHandler()

    private fun parseId(command: String): Int? =
        h.ID_REGEX.find(command)?.destructured?.component1()?.toIntOrNull()

    @Test
    fun plainTimedScheduleParses() {
        val m = h.SCHEDULE.find("0930 : accaScheduleId5; acca.sh --set pause_capacity=60")
        assertNotNull(m)
        val (_, time, command) = m!!.destructured
        assertEquals("0930", time)
        assertEquals(5, parseId(command))
    }

    @Test
    fun bootScheduleWithWaitPrefixParses() {
        val line = "boot until [ -x /dev/.vr25/acc/acca ]; do sleep 1; done; sleep 8; : accaScheduleId7; acca.sh --set pause_capacity=60; : --boot"
        val m = h.SCHEDULE.find(line)
        assertNotNull("boot line must match SCHEDULE", m)
        val (_, time, command) = m!!.destructured
        assertEquals("boot", time)
        assertEquals("the id must be found after the boot wait prefix", 7, parseId(command))
    }

    @Test
    fun onBootTimedScheduleWithWaitPrefixParses() {
        val line = "0700 until [ -x /dev/.vr25/acc/acca ]; do sleep 1; done; sleep 8; : accaScheduleId12; acca.sh --set pause_capacity=80; : --delete; : --boot"
        val m = h.SCHEDULE.find(line)
        assertNotNull(m)
        val (_, _, command) = m!!.destructured
        assertEquals(12, parseId(command))
        assertTrue(h.EXECUTE_ONCE_MATCH_REGEX.matcher(command).find())
        assertTrue(h.EXECUTE_ON_BOOT_MATCH_REGEX.matcher(command).find())
    }

    @Test
    fun idNeverMatchesAPrefixOfALongerId() {
        val cmd = ": accaScheduleId10; acca.sh --set pause_capacity=60"
        assertEquals(10, parseId(cmd))
        assertNull(parseId(": accaScheduleId; broken"))
    }

    @Test
    fun disabledLineStillParses() {
        val m = h.SCHEDULE.find("//0930 : accaScheduleId5; acca.sh --set pause_capacity=60")
        assertNotNull(m)
    }
}

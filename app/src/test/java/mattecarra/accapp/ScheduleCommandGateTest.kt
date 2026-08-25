package mattecarra.accapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import mattecarra.accapp.acc.ConfigUpdater
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.acc.v202107280.AccHandler
import mattecarra.accapp.models.AccConfig

class ScheduleCommandGateTest {
    private val handler = AccHandler(202107280)
    private val config = AccConfig(
        AccConfig.ConfigCapacity(5, 60, 70),
        AccConfig.ConfigVoltage("/sys/x", 4100), 1500,
        AccConfig.ConfigTemperature(40, 45, 90), null, null,
        null, false, false, null, false, false, 60)

    private fun cmd(cue: ConfigUpdaterEnable) =
        ConfigUpdater(config, cue).concatenateCommands(handler)

    @Test fun allEnabledWritesVoltageAndCurrentAndTemperature() {
        val c = cmd(ConfigUpdaterEnable())
        assertTrue(c.contains("voltage", true))
        assertTrue(c.contains("current", true))
        assertTrue(c.contains("temp", true))
    }

    @Test fun voltageOptOutIsHonouredOnTheSchedulePath() =
        assertFalse(cmd(ConfigUpdaterEnable(sendVoltage = false)).contains("voltage", true))

    @Test fun currentOptOutIsHonouredOnTheSchedulePath() =
        assertFalse(cmd(ConfigUpdaterEnable(sendCurrMax = false)).contains("current", true))

    @Test fun temperatureOptOutIsHonouredOnTheSchedulePath() =
        assertFalse(cmd(ConfigUpdaterEnable(sendTemperature = false)).contains("temp", true))

    @Test fun noEmptySegmentEverReachesTheShell() {
        val c = cmd(ConfigUpdaterEnable(sendVoltage = false, sendCurrMax = false))
        assertFalse("`; ;` is a shell syntax error", c.contains("; ;"))
        assertFalse(c.trim().startsWith(";"))
        assertFalse(c.trim().endsWith(";"))
    }
}

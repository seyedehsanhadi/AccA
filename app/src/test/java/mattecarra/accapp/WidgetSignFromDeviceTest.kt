package mattecarra.accapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.chargeStatusWord

/**
 * Readings taken from the two test phones on 2026-08-25, both unplugged at 11%.
 * The widget used to hand `positive = true` to the formatter for every ACC >= 202107280,
 * which prints the raw sign. On laurus the raw sign is positive while draining.
 */
class WidgetSignFromDeviceTest {

    private fun widgetShowsPositive(rawMa: Float, polarity: String, mc: String, st: String): Boolean {
        val norm = AccState.normaliseMilliAmps(rawMa, polarity, mc, st)
        return (norm >= 0f) == (rawMa >= 0f)
    }

    @Test fun laurusInvertedDrainIsShownNegative() {
        val raw = 147.094f
        assertTrue("raw reading is positive", raw > 0f)
        assertFalse(widgetShowsPositive(raw, "inverted", "discharging", "Discharging"))
    }

    @Test fun bluejayNormalDrainKeepsItsSign() {
        assertTrue(widgetShowsPositive(-49.062f, "normal", "discharging", "Discharging"))
    }

    @Test fun unpluggedIsDischargingOnBoth() {
        assertEquals("Discharging", chargeStatusWord(false, "discharging"))
    }

    @Test fun anInputCutIsIdleNotCharging() {
        assertEquals("Idle", chargeStatusWord(true, "cut-input"))
    }

    /**
     * Mi A3 charging, 2026-08-26: --state current_raw -1344605 uA with polarity "inverted", while
     * `acc -i` reported current_now 1.23A -- already polarity-corrected. Normalising the corrected
     * value a second time is what turned the widget negative on a charging phone.
     */
    @Test fun theStateNormalisesItsOwnRawReadingToPositiveWhileCharging() {
        val fromState = AccState.normaliseMilliAmps(-1344.605f, "inverted", "charging", "Charging")
        assertTrue("charging must read positive, was $fromState", fromState > 0f)
    }

    @Test fun normalisingAnAlreadyCorrectedReadingIsWhatWentWrong() {
        val accDashI = 1230f
        val doubled = AccState.normaliseMilliAmps(accDashI, "inverted", "charging", "Charging")
        assertTrue("this is the defect being guarded against", doubled < 0f)
    }
}

package mattecarra.accapp

import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.chargeStatusWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The polarity rule and the status vocabulary, bound where three callers now share them.
 * "unstable" is a dual-path PMIC (a Pixel 6a reports exactly this): the raw sign follows the
 * charge PATH, so it means nothing and the class/status decides.
 */
class PolarityAndStatusTest {

    private fun n(raw: Float, pol: String, cls: String = "", st: String = "") =
        AccState.normaliseMilliAmps(raw, pol, cls, st)

    @Test fun normalPolarityPassesThrough() {
        assertEquals(-350f, n(-350f, "normal"), 0.01f)
        assertEquals(1200f, n(1200f, "normal"), 0.01f)
    }

    // Mi A3: raw reads +1075897 uA while DIScharging.
    @Test fun invertedFlipsTheSign() {
        assertEquals(-1075f, n(1075f, "inverted"), 0.01f)
        assertEquals(1781f, n(-1781f, "inverted"), 0.01f)
    }

    // THE REGRESSION: only "inverted" was handled, so unstable devices kept the meaningless
    // raw sign. Magnitude plus the measured class is the answer.
    @Test fun unstableTakesTheSignFromMeasuredClass() {
        assertEquals(2300f, n(-2300f, "unstable", "charging"), 0.01f)
        assertEquals(-2300f, n(2300f, "unstable", "drain"), 0.01f)
        assertEquals(-2300f, n(2300f, "unstable", "discharging"), 0.01f)
    }

    @Test fun unstableFallsBackToStatusWhenClassIsSilent() {
        assertEquals(900f, n(-900f, "unstable", "", "Charging"), 0.01f)
        assertEquals(-900f, n(900f, "unstable", "", "Discharging"), 0.01f)
    }

    // A cut means the input is held off: cable in, battery not charging. It used to fall through
    // to the "Charging" default and mislabel every input-cut switch.
    @Test fun cutIsIdleNotCharging() {
        assertEquals("Idle", chargeStatusWord(true, "cut"))
        assertEquals("Idle", chargeStatusWord(true, "cut-input"))
    }

    @Test fun theOtherClassesKeepTheirWords() {
        assertEquals("Discharging", chargeStatusWord(false, "charging"))
        assertEquals("Bypass", chargeStatusWord(true, "bypass"))
        assertEquals("Idle", chargeStatusWord(true, "idle"))
        assertEquals("Draining", chargeStatusWord(true, "drain"))
        assertEquals("Charging", chargeStatusWord(true, "charging"))
    }
}

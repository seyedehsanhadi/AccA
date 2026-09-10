package mattecarra.accapp

import mattecarra.accapp.viewmodel.ChargeCaptureViewModel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The Fairphone 5 shape: a 9 V contract on the bus with nothing coming through it. The capture used
 * to call that "FAST charging is WORKING".
 */
class CaptureVerdictTest {

    private fun verdict(vbus: Double?, inW: Double?, pd: Boolean, accClass: String?,
                        accWatts: Double?, charging: Boolean): String =
        ChargeCaptureViewModel.speedVerdict(
            online = true, rt = "USB_PD", vbusV = vbus, inW = inW, pd = pd, vmaxV = 9.0,
            mcc = null, accClass = accClass, accWatts = accWatts, charging = charging)

    @Test fun nineVoltsWithNoPowerIsNotFastCharging() {
        val v = verdict(9.0, 0.0, true, null, 0.0, false)
        assertFalse(v.contains("FAST charging is WORKING"))
        assertTrue(v.contains("not being delivered"))
    }

    @Test fun nineVoltsWithNoMeasurementIsUnproven() {
        val v = verdict(9.0, null, true, null, null, false)
        assertFalse(v.contains("FAST charging is WORKING"))
        assertTrue(v.contains("no power was measured"))
    }

    @Test fun accsOwnFastClassIsBelieved() {
        val v = verdict(9.0, 18.0, true, "fast", 18.0, true)
        assertTrue(v.contains("FAST charging is WORKING"))
    }

    @Test fun accCallingItStandardOverridesTheContract() {
        val v = verdict(9.0, 7.0, true, "standard", 7.0, true)
        assertFalse(v.contains("FAST charging is WORKING"))
    }
}

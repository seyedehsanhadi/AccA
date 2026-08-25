package mattecarra.accapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import mattecarra.accapp.models.chargeStatusWord
import mattecarra.accapp.models.isChargingNow

class WidgetChargingLabelTest {

    @Test fun pauseHoldIsNotChargingEvenWhenTheKernelSaysSo() {
        assertFalse(isChargingNow("cut-input", "Charging"))
        assertFalse(isChargingNow("cut", "Charging"))
        assertFalse(isChargingNow("idle", "Charging"))
        assertFalse(isChargingNow("bypass", "Charging"))
        assertFalse(isChargingNow("standby", "Charging"))
    }

    @Test fun realChargingIsCharging() = assertTrue(isChargingNow("charging", "Charging"))

    @Test fun drainingIsNotCharging() {
        assertFalse(isChargingNow("discharging", "Discharging"))
        assertFalse(isChargingNow("drain", "Charging"))
    }

    @Test fun unknownClassFallsBackToStatus() {
        assertTrue(isChargingNow("", "Charging"))
        assertFalse(isChargingNow("", "Discharging"))
    }

    @Test fun withNeitherClassNorStatusTheCurrentDecides() {
        assertTrue(isChargingNow("", "Unknown", 900f))
        assertFalse(isChargingNow("", "Unknown", -900f))
        assertFalse("a trickle is not charging", isChargingNow("", "Unknown", 40f))
    }

    // Pixel 6a, 2026-08-25, plugged and held at its native level limit: ACC kept reporting
    // measuredClass "charging" while the kernel said "Not charging" and current_raw was -268 mA.
    @Test fun aKernelNegativeOverridesAnOptimisticMeasuredClass() {
        assertFalse(isChargingNow("charging", "Not charging"))
        assertFalse(isChargingNow("charging", "Discharging"))
        assertFalse(isChargingNow("charging", "Full"))
    }

    @Test fun measuredClassStillWinsWhenTheKernelIsNotContradicting() {
        assertTrue(isChargingNow("charging", "Charging"))
        assertTrue(isChargingNow("charging", "Unknown"))
    }

    // Same Pixel 6a reading, on the STATUS WORD rather than the label.
    @Test fun theWordAgreesWithTheRule() {
        assertEquals("Idle", chargeStatusWord(true, "charging", "Not charging"))
        assertEquals("Charging", chargeStatusWord(true, "charging", "Charging"))
        assertEquals("Idle", chargeStatusWord(true, "cut-input", "Charging"))
        assertEquals("Draining", chargeStatusWord(true, "drain", "Discharging"))
        assertEquals("Discharging", chargeStatusWord(false, "charging", "Charging"))
    }

    // Pixel 6a held at a firmware stop: plugged, not charging, and running off the pack.
    // "Idle" next to "-311 mA" contradicted itself.
    @Test fun aPluggedPhoneRunningOffThePackIsDrainingNotIdle() {
        assertEquals("Draining", chargeStatusWord(true, "charging", "Not charging", -311f))
        assertEquals("Draining", chargeStatusWord(true, "", "Not charging", -311f))
    }

    @Test fun aPluggedPhoneSittingStillIsIdle() {
        assertEquals("Idle", chargeStatusWord(true, "charging", "Not charging", -4f))
        assertEquals("Idle", chargeStatusWord(true, "charging", "Not charging", 0f))
        assertEquals("Idle", chargeStatusWord(true, "charging", "Not charging", null))
    }
}

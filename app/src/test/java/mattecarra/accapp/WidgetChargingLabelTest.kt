package mattecarra.accapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}

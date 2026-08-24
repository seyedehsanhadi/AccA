package mattecarra.accapp

import mattecarra.accapp.services.MeterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status-bar meter reports charger-side power while charging and battery-side otherwise.
 * Two rules keep that honest, and both were learned from real defects:
 *   1. watts and amps always come from the SAME side ("Charging 1.0 W 0.00 A" came from mixing);
 *   2. the trigger is measured flow, never the cable (ACC can hold a pause with the cable in).
 */
class MeterSourceTest {

    // 8.5 V x 1.35 A = 11.47 W -> 114 tenths. Measured on a Pixel 6a.
    @Test
    fun chargerWattsFromMillivoltsAndMilliamps() {
        assertEquals(114, MeterSource.wattsX10(8500, 1350, 50))
    }

    // 4.03 V x 2.39 A = 9.63 W -> 96 tenths.
    @Test
    fun batteryWattsFromMillivoltsAndMilliamps() {
        assertEquals(96, MeterSource.wattsX10(4030, 2390, 30))
    }

    @Test
    fun belowThresholdOrMissingGivesNoReading() {
        assertNull(MeterSource.wattsX10(8500, 40, 50))   // under the input floor
        assertNull(MeterSource.wattsX10(null, 1350, 50))
        assertNull(MeterSource.wattsX10(8500, null, 50))
        assertNull(MeterSource.wattsX10(0, 1350, 50))
    }

    // Charging with readable input nodes: report the charger.
    @Test
    fun chargerSideWhenCurrentFlowsIntoTheBattery() {
        assertTrue(MeterSource.useCharger(2390, 114))
    }

    // THE PAUSE CASE, measured: present=1, input_suspend=1, battery at -240 mA. The cable is
    // in, so a plugged-based rule would report charger watts for a phone that is discharging.
    @Test
    fun batterySideWhileAccHoldsAPauseWithTheCableIn() {
        assertFalse(MeterSource.useCharger(-240, 114))
    }

    @Test
    fun batterySideWhenDischarging() {
        assertFalse(MeterSource.useCharger(-580, null))
    }

    // No input telemetry (device without readable input nodes): fall back to the battery.
    @Test
    fun batterySideWhenChargerFiguresAreUnavailable() {
        assertFalse(MeterSource.useCharger(2390, null))
    }

    // Exactly zero is not "into the battery".
    @Test
    fun zeroFlowIsNotChargerSide() {
        assertFalse(MeterSource.useCharger(0, 114))
        assertFalse(MeterSource.useCharger(null, 114))
    }
}

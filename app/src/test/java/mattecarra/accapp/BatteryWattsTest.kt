package mattecarra.accapp

import org.junit.Assert.assertEquals
import org.junit.Test
import mattecarra.accapp.models.AccState

/**
 * Readings from a Pixel 6a on 2026-08-26. charge.watts is the CHARGER side and is always the
 * larger of the two; a row labelled "To battery" must not carry it.
 */
class BatteryWattsTest {

    @Test fun chargingBatterySideIsLowerThanTheCharger() {
        // 2347 mA into a 4.0434 V pack, from an 8.55 V / 1.329 A supply that --state called 11 W.
        assertEquals(9.5f, AccState.batteryWatts(2347f, 4043437L), 0.1f)
    }

    @Test fun drainingIsNegative() =
        assertEquals(-0.5f, AccState.batteryWatts(-130f, 3762000L), 0.1f)

    @Test fun aMillivoltScaleReadingIsFoldedUp() =
        assertEquals(AccState.batteryWatts(1000f, 3800000L), AccState.batteryWatts(1000f, 3800L), 0.01f)

    @Test fun nonsenseVoltageYieldsZeroRatherThanANonsenseWattage() =
        assertEquals(0f, AccState.batteryWatts(2000f, 4L), 0.001f)
}

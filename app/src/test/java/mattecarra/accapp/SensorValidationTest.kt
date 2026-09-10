package mattecarra.accapp

import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.StateFormat
import org.junit.Assert.*
import org.junit.Test

class SensorValidationTest {
    private fun state(raw: String, units: String = "uA", polarity: String = "normal") =
        AccState.parseState("""{"schemaVersion":1,"battery":{"current_raw":$raw,"voltage_raw":4200000,"temp_deci_c":250},"sensing":{"currentUnits":"$units","polarity":"$polarity"}}""")!!

    @Test fun scaleAndSignMatrix() {
        for ((unit, raw) in listOf("uA" to 895000, "µA" to 895000, "μA" to 895000, "mA" to 895)) {
            for (sign in listOf(1, -1)) {
                assertEquals(895f * sign, state((raw * sign).toString(), unit).signedCurrentMilliAmps(), 0.001f)
                assertEquals(-895f * sign, state((raw * sign).toString(), unit, "inverted").signedCurrentMilliAmps(), 0.001f)
            }
        }
        assertEquals(1000f, state("1", "A").signedCurrentMilliAmps(), 0.001f)
    }

    @Test fun invalidReadingsAreUnavailable() {
        for (raw in listOf("null", "\"garbage\"", "1.5", "9223372036854775808")) {
            val value = state(raw).signedCurrentMilliAmps()
            assertTrue(raw, value.isNaN())
            assertEquals("—", StateFormat.current(value, CurrentUnit.mA))
        }
        assertTrue(state("895", "unknown").signedCurrentMilliAmps().isNaN())
        assertTrue(state("895", "mA", "unknown").signedCurrentMilliAmps().isNaN())
        assertTrue(state("895000000", "mA").signedCurrentMilliAmps().isNaN())
        assertEquals(0f, state("0").signedCurrentMilliAmps(), 0f)
    }

    @Test fun modeChangingPolarityUsesFreshEvidence() {
        for (raw in listOf(-895f, 895f)) {
            assertEquals(895f, AccState.normaliseMilliAmps(raw, "unstable", "charging", "Charging"), 0f)
            assertEquals(-895f, AccState.normaliseMilliAmps(raw, "unstable", "drain", "Discharging"), 0f)
            assertTrue(AccState.normaliseMilliAmps(raw, "unstable", "unknown", "Unknown").isNaN())
        }
    }

    @Test fun invalidVoltageAndTemperatureStayUnavailable() {
        assertEquals("—", StateFormat.temperature(Int.MIN_VALUE, TemperatureUnit.C))
        assertEquals("—", StateFormat.voltage(Long.MAX_VALUE, VoltageUnit.V))
        assertEquals("—", StateFormat.voltage(0, VoltageUnit.V))
        assertTrue(AccState.batteryWatts(1000f, Long.MAX_VALUE).isNaN())
        assertEquals(4.2f, AccState.batteryWatts(1000f, 4200000), 0.001f)
        assertEquals(4.2f, AccState.batteryWatts(1000f, 4200), 0.001f)
    }
    @Test fun sensorBoundsApplyBeforeDisplayArithmetic() {
        val s = AccState.parseState("""{"schemaVersion":1,"battery":{"voltage_raw":9223372036854775807,"temp_deci_c":2147483647}}""")!!
        assertEquals(0L, s.voltageRaw)
        assertEquals(Int.MIN_VALUE, s.tempDeciC)
        assertNull(mattecarra.accapp.services.MeterSource.wattsX10(Int.MAX_VALUE, 1000, 0))
        assertNull(mattecarra.accapp.services.MeterSource.wattsX10(5000, Int.MAX_VALUE, 0))
    }

}

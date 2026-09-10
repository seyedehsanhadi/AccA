package mattecarra.accapp

import mattecarra.accapp.models.chargeStatusWord
import mattecarra.accapp.models.isChargingNow
import org.junit.Assert.assertEquals
import mattecarra.accapp.models.AccState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two contradictions a status word must never produce: a word that disagrees with the current
 * printed beside it, and a word invented for a phone that reported nothing.
 */
class StatusWordProbeTest {

    @Test fun aDecisiveCurrentOutranksEveryInference() {
        // ACC says charging, the kernel says not, the pack is taking 1000 mA. The measurement wins.
        assertEquals("Charging", chargeStatusWord(true, "charging", "Not charging", 1000f))
        // A cut that is no longer holding is charging, whatever the class says.
        assertEquals("Charging", chargeStatusWord(true, "cut", "Charging", 1000f))
        // ...and a class of "idle" cannot stand over a measured 1000 mA either.
        assertEquals("Charging", chargeStatusWord(true, "idle", "Charging", 1000f))
        // The negative half, which was already right, must stay right.
        assertEquals("Draining", chargeStatusWord(true, "charging", "Charging", -311f))
        assertEquals(false, isChargingNow("charging", "Charging", -311f))
        assertEquals(true, isChargingNow("idle", "Not charging", 1000f))
    }

    @Test fun idleIsAMeasurementAndAbsenceIsNamed() {
        // Inside the band, in either direction: really still.
        assertEquals("Idle", chargeStatusWord(true, null, null, 3f))
        assertEquals("Idle", chargeStatusWord(true, null, null, -12f))
        // Nothing reported at all.
        assertEquals("Unknown", chargeStatusWord(true, null, null, null))
        // The literal token "unknown" is not a statement about the battery.
        assertEquals("Unknown", chargeStatusWord(true, "unknown", "Unknown", null))
        assertEquals("Unknown", chargeStatusWord(true, null, "Unknown", null))
        // An explicit kernel statement still earns a word without a reading.
        assertEquals("Idle", chargeStatusWord(true, null, "Full", null))
        assertEquals("Idle", chargeStatusWord(true, null, "Not charging", null))
        // Unplugged is unplugged.
        assertEquals("Discharging", chargeStatusWord(false, "charging", "Charging", 1000f))
    }

    /**
     * The real path, not a hand-built Float: parseState -> signedCurrentMilliAmps -> the word.
     * An unreadable current comes back as NaN, and NaN is not null, so every `signedMa != null`
     * guard used to wave it through to "Idle".
     */
    @Test fun anUnreadableCurrentFromARealStateIsUnknown() {
        val unreadable = """
            {"schemaVersion":1,"ts":1782217992,"device":{"model":"MI A3"},
             "acc":{"version":"v2025.5.18-6.4-rc25","versionCode":"202505347"},
             "battery":{"capacityPct":75,"current_raw":300140,"voltage_raw":4029569,"temp_deci_c":298,"status":"Unknown"},
             "config":{},
             "plugged":true,"native":{"enabled":false},
             "sensing":{"currentUnits":"?","polarity":"normal","statusTrust":"trusted","confidence":"low"},
             "switch":{"measuredClass":"unknown"}}
        """.trimIndent()
        val s = AccState.parseState(unreadable)
        assertNotNull(s)
        s!!
        assertTrue("the fixture must be genuinely unreadable", s.signedCurrentMilliAmps().isNaN())
        assertEquals("Unknown",
            chargeStatusWord(s.plugged, s.measuredClass, s.status, s.signedCurrentMilliAmps()))
        assertEquals(false, isChargingNow(s.measuredClass, s.status, s.signedCurrentMilliAmps()))
    }
}

package mattecarra.accapp

import mattecarra.accapp.models.AccState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccStateTest {

    // Real rc12 `acca --state` capture from the Mi A3 (input_suspend device, cable in + cutting).
    private val rc12Fixture = """
        {"schemaVersion":1,"ts":1782217992,"device":{"model":"MI A3"},
         "acc":{"version":"v2025.5.18-6.4-rc12","versionCode":"202505229"},
         "battery":{"capacityPct":75,"current_raw":300140,"voltage_raw":4029569,"temp_deci_c":298,"status":"Discharging"},
         "config":{"capacity":"5 101 72 74 false","temperature":"45 50 40 55","chargingSwitch":"battery/input_suspend 0 1 --","allowIdleAbovePcap":"false","prioritizeBattIdleMode":"true"},
         "plugged":true,"native":{"enabled":false},
         "sensing":{"currentUnits":"uA","polarity":"inverted","statusTrust":"trusted","confidence":"high"},
         "switch":{"locked":"battery/input_suspend 0 1 --","userLocked":true,"measuredClass":"discharging"}}
    """.trimIndent()

    @Test
    fun parseState_realRc12Fixture_extractsFields() {
        val s = AccState.parseState(rc12Fixture)
        assertNotNull("rc12 state must parse", s)
        s!!
        assertEquals(1, s.schemaVersion)
        assertEquals(75, s.capacityPct)
        assertEquals(300140L, s.currentRaw)
        assertEquals(4029569L, s.voltageRaw)
        assertEquals(298, s.tempDeciC)
        assertEquals("Discharging", s.status)
        assertTrue("plugged must be true even while cutting", s.plugged)
        assertEquals("uA", s.currentUnits)
        assertEquals("inverted", s.polarity)
        assertTrue("userLocked must be true", s.userLocked)
        assertEquals("discharging", s.measuredClass)
        assertEquals(202505229, s.accVersionCode)
    }

    @Test
    fun parseState_inputTelemetry_presentAndNormalized() {
        // rc11 adds "input" (charger-side volts/amps, already mV/mA from the daemon). Real Pixel 9a
        // numbers from a 9V fast charge with a 1000 mA input limit applied.
        val json = """
            {"schemaVersion":1,"battery":{"capacityPct":56,"current_raw":1807000,"voltage_raw":4104000,"temp_deci_c":289,"status":"Charging"},
             "plugged":true,"input":{"voltageMv":9000,"currentMa":976},"native":{"enabled":true,"stopLevel":80},
             "sensing":{"currentUnits":"uA","polarity":"normal"},"switch":{"userLocked":false,"measuredClass":"charging"}}
        """.trimIndent()
        val s = AccState.parseState(json)!!
        assertEquals(9000, s.inputVoltageMv)
        assertEquals(976, s.inputCurrentMa)
    }

    @Test
    fun parseState_chargeBlock_presentParsesAllFields() {
        // rc12-engine charge block: physics-only class. Real Pixel 9a 9V numbers -> 19W fast.
        val json = """
            {"schemaVersion":1,"battery":{"capacityPct":58,"current_raw":4508593,"voltage_raw":4251171,"temp_deci_c":384,"status":"Charging"},
             "plugged":true,"input":{"voltageMv":8950,"currentMa":2148},
             "charge":{"watts":19,"class":"fast","reason":null,"approx":false},
             "sensing":{"currentUnits":"uA","polarity":"normal"},"switch":{"userLocked":true,"measuredClass":"charging"}}
        """.trimIndent()
        val s = AccState.parseState(json)!!
        assertEquals(19, s.chargeWatts)
        assertEquals("fast", s.chargeClass)
        assertNull("null reason must stay null", s.chargeReason)
        assertFalse(s.chargeApprox)
    }

    @Test
    fun parseState_chargeBlock_reasonAndApprox() {
        val json = """
            {"schemaVersion":1,"battery":{"capacityPct":96,"current_raw":400000,"voltage_raw":4400000,"temp_deci_c":300,"status":"Charging"},
             "plugged":true,"input":{"voltageMv":null,"currentMa":null},
             "charge":{"watts":2,"class":"slow","reason":"taper","approx":true},
             "sensing":{"currentUnits":"uA","polarity":"normal"},"switch":{"userLocked":false,"measuredClass":"charging"}}
        """.trimIndent()
        val s = AccState.parseState(json)!!
        assertEquals(2, s.chargeWatts)
        assertEquals("slow", s.chargeClass)
        assertEquals("taper", s.chargeReason)
        assertTrue(s.chargeApprox)
    }

    @Test
    fun parseState_chargeBlockAbsent_yieldsNulls() {
        // Old daemon (pre charge block) -> all null, dashboard line hides.
        val s = AccState.parseState(rc12Fixture)!!
        assertNull(s.chargeWatts)
        assertNull(s.chargeClass)
        assertNull(s.chargeReason)
        assertFalse(s.chargeApprox)
    }

    @Test
    fun parseState_inputAbsentOrNull_yieldsNull() {
        // No "input" object at all (old daemon).
        val noBlock = AccState.parseState(rc12Fixture)!!
        assertNull("absent input block -> null volts", noBlock.inputVoltageMv)
        assertNull("absent input block -> null amps", noBlock.inputCurrentMa)
        // "input" present but JSON null (device with no readable input node) -> null, not 0.
        val jsonNull = """
            {"schemaVersion":1,"battery":{"capacityPct":50,"current_raw":0,"voltage_raw":4000000,"temp_deci_c":250,"status":"Charging"},
             "plugged":true,"input":{"voltageMv":null,"currentMa":null},
             "sensing":{"currentUnits":"uA","polarity":"normal"},"switch":{"userLocked":false,"measuredClass":"bypass"}}
        """.trimIndent()
        val s = AccState.parseState(jsonNull)!!
        assertNull(s.inputVoltageMv)
        assertNull(s.inputCurrentMa)
    }

    @Test
    fun parseState_invertedPolarity_signedCurrentIsNegative() {
        val s = AccState.parseState(rc12Fixture)!!
        // current_raw 300140 uA = 300.14 mA; polarity inverted -> negative (discharging).
        val signed = s.signedCurrentMilliAmps()
        assertTrue("inverted uA current must normalise negative, was $signed", signed < 0f)
        assertEquals(-300.14f, signed, 0.01f)
    }

    @Test
    fun parseState_normalPolarityMilliAmps_keepsSignAndScale() {
        val json = """
            {"schemaVersion":1,"battery":{"capacityPct":50,"current_raw":-512,"voltage_raw":4000,"temp_deci_c":250,"status":"Charging"},
             "plugged":true,"sensing":{"currentUnits":"mA","polarity":"normal"},
             "switch":{"userLocked":false,"measuredClass":"charging"}}
        """.trimIndent()
        val s = AccState.parseState(json)!!
        // mA units -> no /1000; normal polarity -> no flip. Raw -512 stays -512.
        assertEquals(-512f, s.signedCurrentMilliAmps(), 0.001f)
        assertFalse(s.userLocked)
        assertEquals("charging", s.measuredClass)
    }

    @Test
    fun parseState_empty_returnsNull() {
        assertNull(AccState.parseState(""))
        assertNull(AccState.parseState("   "))
    }

    @Test
    fun parseState_malformed_returnsNull() {
        assertNull(AccState.parseState("not json at all"))
        assertNull(AccState.parseState("{ broken"))
    }

    @Test
    fun parseState_schemaVersionBelowOne_returnsNull() {
        // schemaVersion 0 / missing -> unknown contract -> caller must fall back to legacy.
        assertNull(AccState.parseState("""{"schemaVersion":0,"plugged":true}"""))
        assertNull(AccState.parseState("""{"battery":{"capacityPct":50}}"""))
    }

    // ---- current_max upper-bound rule (PowerLimitDialogExt.checkCurrent) ----
    // The dialog enables OK only when the entered current is in 1..9999 (a >9999 write is
    // rejected by the daemon and applies NO limit). Mirror that exact predicate here.
    private fun currentMaxValid(value: Int?) = mattecarra.accapp.models.ConfigLimits.currentMaxValid(value)

    @Test
    fun currentMaxBound_acceptsInRange_rejectsOutOfRange() {
        assertTrue(currentMaxValid(1))
        assertTrue(currentMaxValid(500))
        assertTrue(currentMaxValid(9999))
        assertFalse(currentMaxValid(0))
        assertFalse(currentMaxValid(-1))
        assertFalse(currentMaxValid(10000))
        assertFalse(currentMaxValid(99999))
        assertFalse(currentMaxValid(null))
    }

    // ---- shutdown_temp rule (AccConfigEditorActivity.validateConfig) ----
    // shutdown ∈ [max(max_temp,40) .. 70]. Returns true when the config is acceptable.
    // Delegates to the PRODUCTION rule now. These were local re-implementations, so the
    // assertions passed whether or not the real checks still existed.
    private fun shutdownTempValid(maxTemp: Int, shutdown: Int) =
        mattecarra.accapp.models.ConfigLimits.shutdownTempValid(maxTemp, shutdown)

    @Test
    fun shutdownTempBound_respectsMaxFloorAndCeiling() {
        // max_temp below 40 -> floor is 40.
        assertTrue(shutdownTempValid(35, 40))
        assertFalse(shutdownTempValid(35, 39))
        // max_temp above 40 -> floor is max_temp.
        assertTrue(shutdownTempValid(50, 50))
        assertFalse(shutdownTempValid(50, 49))
        // ceiling is 70 regardless.
        assertTrue(shutdownTempValid(50, 70))
        assertFalse(shutdownTempValid(50, 71))
        // typical default config (max 50, shutdown 55) is valid.
        assertTrue(shutdownTempValid(50, 55))
    }
}

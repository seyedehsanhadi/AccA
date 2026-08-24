package mattecarra.accapp

import mattecarra.accapp.models.BatteryInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The current/voltage converters carry the whole dashboard and widget reading. ACC 2025.x
 * emits `current_now -0.35A` and `voltage_now 3.83V` (amps/volts, polarity already applied
 * by the daemon), and the default input unit is A/V, so the chain must land on -350 mA.
 */
class BatteryInfoConvertersTest {

    private fun info(currentNow: Float = 0f, voltageNow: Float = 0f) = BatteryInfo(
        name = "battery", isInputSuspend = false, status = "Discharging", health = "Good",
        present = 1, chargeType = "USB", capacity = 50, chargerTemp = -1, chargerTempMax = -1,
        isInputCurrentLimited = false, voltageNow = voltageNow, voltageMax = -1, voltageQnovo = -1,
        currentNow = currentNow, currentQnovo = -1, constantChargeCurrentMax = -1,
        temperature = 25, technology = "Li-ion", isStepChargingEnabled = false,
        isSwJeitaEnabled = false, isTaperControlEnabled = false, isChargeDisabled = false,
        isChargeDone = false, isParallelDisabled = false,
        setShipMode = false, dieHealth = "Unknown", rerunAicl = false, dpDm = false,
        chargeControlLimitMax = -1, chargeControlLimit = -1, inputCurrentMax = -1, cycleCount = -1
    )

    // ACC 2025.x hands out AMPS. Default input unit is A, so -0.35 A must read -350 mA.
    @Test
    fun ampsInputConvertsToMilliamps() {
        assertEquals(-350f, info(currentNow = -0.35f).getCurrentNow(CurrentUnit.A), 0.01f)
    }

    // The Mi A3's raw node is +193023 uA while DIScharging; ACC flips it before we see it,
    // so a negative reading must stay negative through the converter.
    @Test
    fun negativeCurrentKeepsItsSign() {
        val out = info(currentNow = -0.35f).getCurrentNow(CurrentUnit.A, CurrentUnit.mA, true, true)
        assertEquals("-350 mA", out)
    }

    @Test
    fun microampsInputConvertsToMilliamps() {
        assertEquals(-350f, info(currentNow = -350000f).getCurrentNow(CurrentUnit.uA), 0.01f)
    }

    // The magnitude guard folds an impossible reading back rather than printing millions of mA.
    @Test
    fun outOfRangeMagnitudeIsFoldedBack() {
        val out = info(currentNow = -350000f).getCurrentNow(CurrentUnit.A)
        assertEquals(-350f, out, 0.01f)
    }

    @Test
    fun voltageNormalisesByMagnitudeNotStoredUnit() {
        assertEquals(3.83f, info(voltageNow = 3.83f).getVoltageNow(VoltageUnit.V), 0.001f)
        assertEquals(3.83f, info(voltageNow = 3830f).getVoltageNow(VoltageUnit.V), 0.001f)
        assertEquals(3.83f, info(voltageNow = 3830000f).getVoltageNow(VoltageUnit.V), 0.001f)
    }

    @Test
    fun voltageOutputInMillivolts() {
        assertEquals("3830 mV", info(voltageNow = 3.83f).getVoltageNow(VoltageUnit.V, VoltageUnit.mV, true))
    }

    @Test
    fun zeroVoltageIsPassedThroughUntouched() {
        assertEquals(0f, info(voltageNow = 0f).getVoltageNow(VoltageUnit.V), 0.001f)
    }
}

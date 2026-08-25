package mattecarra.accapp

import org.junit.Assert.assertEquals
import org.junit.Test
import mattecarra.accapp.models.AccConfig

class CoolDownPercentCarryTest {
    private fun cfg(percent: Int, ratio: AccConfig.ConfigCoolDown?) =
        AccConfig(
            AccConfig.ConfigCapacity(5, 60, 70),
            AccConfig.ConfigVoltage(null, null), null,
            AccConfig.ConfigTemperature(40, 45, 90), null, null,
            ratio, false, false, null, false, false, percent)

    @Test fun ratioWins() =
        assertEquals(80, cfg(60, AccConfig.ConfigCoolDown(80, 50, 10)).coolDownPercent())

    @Test fun capacityCarriesWhenRatioCleared() =
        assertEquals(80, cfg(80, null).coolDownPercent())

    @Test fun sentinelMeansOff() =
        assertEquals(101, cfg(101, null).coolDownPercent())
}

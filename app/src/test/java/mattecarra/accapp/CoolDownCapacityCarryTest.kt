package mattecarra.accapp

import mattecarra.accapp.models.AccConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cool-down PERCENTAGE (ACC's cooldown_capacity) is its own key and is not driven by any
 * picker. Anything that rebuilds an AccConfig must carry it, or the constructor default supplies
 * 101 -- ACC's "cool-down off" -- and a value the user never touched is reset.
 *
 * This has now bitten three separate paths: the live save, the schedule/DJS command builder, and
 * the editor's profile getter. The invariant is one line; the call sites are many.
 */
class CoolDownCapacityCarryTest {

    private fun cfg(pct: Int) = AccConfig(configCoolDownCapacity = pct)

    @Test
    fun defaultIsAccsOffSentinel() {
        assertEquals(101, AccConfig().configCoolDownCapacity)
    }

    // The editor rebuilds AccConfig field by field. Rebuilding without the field is the defect.
    @Test
    fun rebuildingMustCarryThePercentage() {
        val stored = cfg(60)
        val rebuiltWrong = AccConfig(
            stored.configCapacity, stored.configVoltage, stored.configCurrMax,
            stored.configTemperature, stored.configOnBoot, stored.configOnPlug,
            stored.configCoolDown, stored.configResetUnplugged, stored.configResetBsOnPause,
            stored.configChargeSwitch, stored.configIsAutomaticSwitchingEnabled,
            stored.prioritizeBatteryIdleMode)
        assertEquals(101, rebuiltWrong.configCoolDownCapacity)   // what the bug looked like

        val rebuiltRight = AccConfig(
            stored.configCapacity, stored.configVoltage, stored.configCurrMax,
            stored.configTemperature, stored.configOnBoot, stored.configOnPlug,
            stored.configCoolDown, stored.configResetUnplugged, stored.configResetBsOnPause,
            stored.configChargeSwitch, stored.configIsAutomaticSwitchingEnabled,
            stored.prioritizeBatteryIdleMode, stored.configCoolDownCapacity)
        assertEquals(60, rebuiltRight.configCoolDownCapacity)
    }

    // Both writers pick the percentage the same way: the ratio's value when a ratio exists,
    // otherwise the standalone key -- never a hardcoded literal.
    // Bind the REAL rule both writers call, so a literal creeping back fails here.
    private fun chosen(c: AccConfig) = c.coolDownPercent()

    @Test
    fun ratioPresentWins() {
        val c = AccConfig(configCoolDown = AccConfig.ConfigCoolDown(60, 50, 10),
                          configCoolDownCapacity = 101)
        assertEquals(60, chosen(c))
    }

    @Test
    fun ratioClearedKeepsTheStandalonePercentage() {
        assertEquals(70, chosen(AccConfig(configCoolDown = null, configCoolDownCapacity = 70)))
    }

    @Test
    fun offStaysOff() {
        assertEquals(101, chosen(AccConfig(configCoolDown = null, configCoolDownCapacity = 101)))
    }
}

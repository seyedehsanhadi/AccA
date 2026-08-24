package mattecarra.accapp

import mattecarra.accapp.models.AccConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACC writes `cooldown_capacity=101` to mean "never start cooling down by capacity" — a
 * percentage cannot exceed 100. The app used to render that sentinel as "Start at: 101%",
 * and the editor's 0..100 picker clamped it to 100, which silently turned the capacity
 * trigger ON at 100% the next time a config was saved.
 */
class CoolDownOffSentinelTest {

    @Test
    fun sentinelIsRecognisedAsOff() {
        assertTrue(AccConfig.ConfigCoolDown(101, 50, 10).isCapacityTriggerOff)
    }

    @Test
    fun realPercentagesAreNotOff() {
        assertFalse(AccConfig.ConfigCoolDown(60, 50, 10).isCapacityTriggerOff)
        assertFalse(AccConfig.ConfigCoolDown(100, 50, 10).isCapacityTriggerOff)
        assertFalse(AccConfig.ConfigCoolDown(0, 50, 10).isCapacityTriggerOff)
    }

    // 100 is a legitimate setting and must stay distinguishable from the off sentinel.
    @Test
    fun hundredIsAValidSettingNotOff() {
        assertFalse(AccConfig.ConfigCoolDown(100, 50, 10).isCapacityTriggerOff)
        assertTrue(AccConfig.ConfigCoolDown(101, 50, 10).isCapacityTriggerOff)
    }

    // The ratio survives the capacity trigger being off: ACC still cools down by temperature.
    @Test
    fun ratioIsPreservedWhileCapacityTriggerIsOff() {
        val c = AccConfig.ConfigCoolDown(101, 50, 10)
        assertTrue(c.isCapacityTriggerOff)
        assertTrue(c.charge == 50 && c.pause == 10)
    }

    // THE REGRESSION: seeding the editor with the sentinel meant flipping the section switch
    // ON rebuilt ConfigCoolDown(101, ...), so ACC kept cool-down OFF and the row stayed hidden
    // right after the user enabled it. Enabling must always yield a real percentage.
    @Test
    fun enablingNeverCarriesTheOffSentinel() {
        assertEquals(60, AccConfig.ConfigCoolDown(101, 50, 10).editorPercent())
        assertFalse(AccConfig.ConfigCoolDown(AccConfig.ConfigCoolDown(101, 50, 10).editorPercent(), 50, 10).isCapacityTriggerOff)
    }

    // A real percentage must survive the editor untouched, including the 100 edge.
    @Test
    fun realPercentagesRoundTripThroughTheEditor() {
        assertEquals(60, AccConfig.ConfigCoolDown(60, 50, 10).editorPercent())
        assertEquals(100, AccConfig.ConfigCoolDown(100, 50, 10).editorPercent())
        assertEquals(0, AccConfig.ConfigCoolDown(0, 50, 10).editorPercent())
    }
}

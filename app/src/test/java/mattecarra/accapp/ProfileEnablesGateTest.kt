package mattecarra.accapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.models.ProfileEnables

class ProfileEnablesGateTest {
    private val all = ConfigUpdaterEnable()

    @Test fun temperatureOffInProfileIsNotSent() =
        assertFalse(all.forProfile(ProfileEnables(eTemperature = false)).sendTemperature)

    @Test fun temperatureOnStaysSent() =
        assertTrue(all.forProfile(ProfileEnables(eTemperature = true)).sendTemperature)

    @Test fun globalOffWinsOverProfileOn() =
        assertFalse(all.copy(sendTemperature = false)
            .forProfile(ProfileEnables(eTemperature = true)).sendTemperature)
}

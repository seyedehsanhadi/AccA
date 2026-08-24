package mattecarra.accapp

import mattecarra.accapp.acc.v202107280.AccHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `acca -ss::` is the vetted switch list. Its lines carry an ordinal, a [class] marker,
 * an optional {key} annotation and CRLF endings; callers need the bare "node on off" spec.
 * The old code called `-s s:` and got 21 raw candidates on a Pixel 6a against these 6.
 */
class SwitchListParseTest {

    private val handler = AccHandler(202505333)

    @Test
    fun stripsOrdinalClassMarkerAndAnnotation() {
        val out = handler.parseSwitchList(listOf(
            " 1) [d] battery/input_suspend 0 1\r",
            " 2) [d] main/current_max 3000000 0 {mcc}\r"
        ))
        assertEquals(listOf("battery/input_suspend 0 1", "main/current_max 3000000 0"), out)
    }

    @Test
    fun handlesRealPixelOutput() {
        val out = handler.parseSwitchList(listOf(
            " 1) [d] /sys/devices/platform/google,charger/charge_stop_level 100 pcap\r",
            " 2) [d] main-charger/current_max 3000000 0 {mcc}\r",
            " 3) [d] usb/current_max 5000000 10000 {mcc}\r"
        ))
        assertEquals(3, out.size)
        assertEquals("/sys/devices/platform/google,charger/charge_stop_level 100 pcap", out[0])
        assertTrue(out.none { it.contains("{") })
        assertTrue(out.none { it.contains("\r") })
    }

    @Test
    fun dropsBlankAndUnparseableLines() {
        val out = handler.parseSwitchList(listOf("", "   ", "not a numbered entry", " 1) [d] battery/input_suspend 0 1"))
        assertEquals(listOf("battery/input_suspend 0 1"), out)
    }

    @Test
    fun deduplicatesRepeatedSpecs() {
        val out = handler.parseSwitchList(listOf(
            " 1) [d] battery/input_suspend 0 1",
            " 2) [d] battery/input_suspend 0 1 {mcc}"
        ))
        assertEquals(1, out.size)
    }

    @Test
    fun toleratesMissingClassMarker() {
        val out = handler.parseSwitchList(listOf(" 7) battery/charging_enabled 1 0"))
        assertEquals(listOf("battery/charging_enabled 1 0"), out)
    }
}

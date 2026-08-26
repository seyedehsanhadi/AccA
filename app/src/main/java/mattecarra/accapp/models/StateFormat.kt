package mattecarra.accapp.models

import mattecarra.accapp.CurrentUnit
import mattecarra.accapp.TemperatureUnit
import mattecarra.accapp.VoltageUnit

/**
 * Rendering of the --state figures, shared by every surface that shows them.
 *
 * The dashboard prefers --state for temperature and voltage and says why: reading those from the
 * `acca -i` scrape while amps and status came from --state left the card split-brained. The widget
 * was still doing exactly that, so the two disagreed on the same phone at the same moment -- 4.040 V
 * against 4.036 V, from `acc -i` rounding to two decimals before the widget printed three.
 */
object StateFormat {

    fun current(signedMilliAmps: Float, unit: CurrentUnit): String =
        if (unit == CurrentUnit.A) String.format("%.3f", signedMilliAmps / 1000f) + " A"
        else signedMilliAmps.toInt().toString() + " mA"

    /** --state reports deci-Celsius (230 = 23.0C), the scale batteryInfo exposes after its own /10. */
    fun temperature(tempDeciC: Int, unit: TemperatureUnit): String {
        val c = tempDeciC / 10f
        return if (unit == TemperatureUnit.C) c.toInt().toString() + " " + Typography.degree + "C"
        else String.format("%.1f", c * 1.8f + 32f) + " " + Typography.degree + "F"
    }

    /**
     * voltage_raw is microvolts on every device seen so far, but ACC does not promise it: fold a
     * millivolt-scale reading up rather than printing 0.004 V.
     */
    fun voltage(voltageRaw: Long, unit: VoltageUnit): String {
        val mV = if (voltageRaw >= 100000L) voltageRaw / 1000L else voltageRaw
        return if (unit == VoltageUnit.V) String.format("%.3f", mV / 1000f) + " V"
        else mV.toString() + " mV"
    }
}

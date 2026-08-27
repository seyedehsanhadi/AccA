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

    /**
     * Every one of these enums has THREE values, and treating them as two silently renders the
     * third as its neighbour. currentOutputUnitOfMeasure really can hold uA (Preferences maps the
     * stored string "uA"), so `unit == A` else mA printed microamps with a mA suffix.
     */
    fun current(signedMilliAmps: Float, unit: CurrentUnit): String = when (unit) {
        CurrentUnit.A  -> String.format("%.3f", signedMilliAmps / 1000f) + " A"
        CurrentUnit.uA -> (signedMilliAmps * 1000f).toLong().toString() + " µA"
        else           -> signedMilliAmps.toInt().toString() + " mA"
    }

    /**
     * --state reports deci-Celsius (230 = 23.0C), the scale batteryInfo exposes after its own /10.
     *
     * CF means BOTH scales, and it is the DEFAULT preference, so the old two-way test
     * (`== C` else Fahrenheit) changed what every stock install shows the moment the dashboard and
     * widget moved onto this formatter: Celsius silently became Fahrenheit-only. ChargeMeterService
     * already renders the three-way correctly; this now matches it rather than contradicting it on
     * the same screen.
     */
    fun temperature(tempDeciC: Int, unit: TemperatureUnit): String {
        val c = tempDeciC / 10f
        val f = c * 1.8f + 32f
        val deg = Typography.degree
        return when (unit) {
            TemperatureUnit.C  -> c.toInt().toString() + " " + deg + "C"
            TemperatureUnit.F  -> String.format("%.1f", f) + " " + deg + "F"
            else               -> c.toInt().toString() + deg + "C/" + Math.round(f).toString() + deg + "F"
        }
    }

    /**
     * voltage_raw is microvolts on every device seen so far, but ACC does not promise it: fold a
     * millivolt-scale reading up rather than printing 0.004 V.
     */
    fun voltage(voltageRaw: Long, unit: VoltageUnit): String {
        val mV = if (voltageRaw >= 100000L) voltageRaw / 1000L else voltageRaw
        return when (unit) {
            VoltageUnit.V  -> String.format("%.3f", mV / 1000f) + " V"
            VoltageUnit.uV -> (mV * 1000L).toString() + " µV"
            else           -> mV.toString() + " mV"
        }
    }
}

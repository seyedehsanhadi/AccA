package mattecarra.accapp

import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.StateFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SensorMatrixTest {
    @Test fun conversionAndFaultMatrix() {
        val rows = javaClass.classLoader!!.getResourceAsStream("sensor-matrix.tsv")!!.bufferedReader().use { it.readLines().drop(1) }
        repeat(10) {
            for (line in rows) {
                val c = line.split('\t')
                val id = "${c[0]} ${c[1]}"
                val units = when (c[5]) { "1000" -> "mA"; "1000000" -> "uA"; else -> "unknown" }
                val root = JSONObject().put("schemaVersion", 1)
                    .put("battery", JSONObject().put("current_raw", c[3].replace("~", ""))
                        .put("voltage_raw", c[2].replace("~", "")))
                    .put("sensing", JSONObject().put("currentUnits", units).put("polarity", c[6]))
                    .put("charge", JSONObject().put("watts", if (c[9] == "null") JSONObject.NULL else c[9].toDouble()))
                val state = AccState.parseState(root.toString())!!
                val ma = state.signedCurrentMilliAmps()
                if (c[11] == "null") {
                    assertTrue("$id current", ma.isNaN())
                    assertEquals("$id invalid display", "—", StateFormat.current(ma, CurrentUnit.mA))
                } else assertEquals("$id current", c[11].toDouble(), ma.toDouble(), maxOf(0.00001, kotlin.math.abs(c[11].toDouble()) * 0.000001))
                val watts = AccState.batteryWatts(ma, state.voltageRaw)
                if (c[12] == "null") assertTrue("$id watts", watts.isNaN())
                else assertEquals("$id watts", c[12].toDouble(), watts.toDouble(), maxOf(0.000001, kotlin.math.abs(c[12].toDouble()) * 0.000001))
                val voltage = StateFormat.voltage(state.voltageRaw, VoltageUnit.uV)
                assertEquals("$id voltage", if (c[13] == "null") "—" else "${c[13]} µV", voltage)
                if (c[9] == "null") assertNull("$id charge watts", state.chargeWatts)
                else assertEquals("$id charge watts", c[9].toDouble(), state.chargeWatts!!, 0.0000001)
            }
        }
        println("AccA matrix: ${rows.size} scenarios x 10 repetitions")
    }

    @Test fun directionEvidenceSurvivesModeAndScaleChanges() {
        var count = 0
        for (voltage in listOf(5000, 9000, 5000, 9000))
            for ((units, magnitude) in listOf("mA" to 895, "uA" to 895000))
                for (sign in listOf(-1, 1))
                    for (polarity in listOf("normal", "inverted", "unstable", "unknown"))
                        for (direction in listOf("rising", "falling")) {
                            val json = """{"schemaVersion":1,"plugged":true,"battery":{"current_raw":${sign * magnitude},"voltage_raw":$voltage,"status":"Unknown"},"sensing":{"currentUnits":"$units","polarity":"$polarity","ccDir":"$direction"}}"""
                            val state = AccState.parseState(json)!!
                            val expected = if (direction == "rising") 895f else -895f
                            assertEquals("$voltage $units $sign $polarity $direction", expected, state.signedCurrentMilliAmps(), 0.001f)
                            count++
                        }
        println("AccA mode transitions: $count")
    }

    @Test fun malformedTelemetryCannotWrapIntoPlausibleNumbers() {
        for (raw in listOf("garbage", "NaN", "Infinity", "-1", "4294972296", "50001", "9.5")) {
            val json = JSONObject().put("schemaVersion", 1).put("battery", JSONObject()).put("input", JSONObject().put("voltageMv", raw))
            assertNull(raw, AccState.parseState(json.toString())!!.inputVoltageMv)
        }
        for (raw in listOf("garbage", "NaN", "Infinity", "-1", "5001", "9223372036854775808")) {
            val json = JSONObject().put("schemaVersion", 1).put("battery", JSONObject()).put("charge", JSONObject().put("watts", raw))
            assertNull(raw, AccState.parseState(json.toString())!!.chargeWatts)
        }
    }
}

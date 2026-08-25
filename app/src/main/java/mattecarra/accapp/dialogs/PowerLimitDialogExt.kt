package mattecarra.accapp.dialogs

import android.renderscript.ScriptGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import androidx.core.view.isVisible
import mattecarra.accapp.databinding.VoltageControlEditorDialogBinding
import mattecarra.accapp.models.AccConfig

typealias PowerLimitSelectionListener = ((voltageControlFile: String?, voltageLimitEnabled: Boolean,
                                          voltageMax: Int?, currentLimitEnabled: Boolean,
                                          currentMax: Int?) -> Unit)

/**
 * Names the charger tier from the live input voltage (mV). USB-PD standard PDOs are 5/9/12/15/20 V;
 * PPS delivers off-nominal values (4.9, 8.9, ...), so classify by band, not exact match. The band
 * edges sit at the midpoints between tiers. Device-measured on a Pixel 9a: 8975 mV -> "9V fast"
 * (ratio x1.8), 4650 mV -> "5V standard" (ratio x1.0).
 */
fun chargerTierLabel(context: android.content.Context, mv: Int): String = context.getString(
    when {
        mv < 7000  -> R.string.charger_tier_5v
        mv < 10500 -> R.string.charger_tier_9v
        mv < 13500 -> R.string.charger_tier_12v
        mv < 17500 -> R.string.charger_tier_15v
        else       -> R.string.charger_tier_20v
    }
)

/**
 * `voltageSendable` / `currentSendable` are the Settings opt-outs (cueVoltage, cueCurrMax). They
 * are INDEPENDENT flags and must gate independently: ConfigUpdater reads them separately, so with
 * one off and one on the skipped half was still editable here and its write -- including a clear --
 * went nowhere. Never show a field whose command is skipped.
 */
fun MaterialDialog.powerLimitDialog(
    configVoltage: AccConfig.ConfigVoltage,
    configCurrentMax: Int?,
    coroutineScope: CoroutineScope,
    voltageSendable: Boolean = true,
    currentSendable: Boolean = true,
    listener: PowerLimitSelectionListener
): MaterialDialog {

    val binding = VoltageControlEditorDialogBinding.inflate(layoutInflater)
    customView(view = binding.root, scrollable = true)
    title(R.string.edit_power_limit)

    var inputVoltageMaxOk = true
    var inputCurrentMaxOK = true
    var inputVoltageControlFileOk = true

    val voltageControlFileLayout = binding.voltageControlFileDialogLl
    val voltageControlSpinner = binding.voltageControlFileSpinner

    val enableVoltageLimitCheckBox = binding.enableVoltageMaxCheckBox
    val voltageMaxEditText = binding.voltageMaxEditText

    val currentMaxLayout = binding.currentMaxDialogLl
    val enableCurrentLimitCheckBox = binding.enableCurrentMaxCheckBox
    val currentMaxEditText = binding.currentMaxEditText

    if (!voltageSendable) {
        voltageControlFileLayout.isVisible = false
        enableVoltageLimitCheckBox.isEnabled = false
        voltageMaxEditText.isEnabled = false
        enableVoltageLimitCheckBox.text = context.getString(R.string.power_limit_off_in_settings,
            context.getString(R.string.cue_AccVoltControl_pref_title))
    }
    if (!currentSendable) {
        enableCurrentLimitCheckBox.isEnabled = false
        currentMaxEditText.isEnabled = false
        enableCurrentLimitCheckBox.text = context.getString(R.string.power_limit_off_in_settings,
            context.getString(R.string.cue_AccCurrentMax_pref_title))
    }

    positiveButton(android.R.string.ok) { dialog ->

        val voltageMaxInt = voltageMaxEditText.text.toString().toIntOrNull()
        val currentMaxInt = currentMaxEditText.text.toString().toIntOrNull()

        listener(voltageControlSpinner.selectedItem as String?, enableVoltageLimitCheckBox.isChecked,
            voltageMaxInt, enableCurrentLimitCheckBox.isChecked, currentMaxInt)
    }

    // VOLTAGE MAX SELECTION --------------------------------------------------------------------

    fun hideHintErrVolt(hide: Boolean)
    {
        voltageMaxEditText.error = if (hide) null else context.getString(R.string.invalid_voltage_max)
    }

    fun checkVolt(value: String?)
    {
        // toIntOrNull: non-numeric or overflowing EditText input must invalidate
        // the field, never crash the dialog.
        inputVoltageMaxOk = (value?.toIntOrNull() ?: -1) in 3700..4300
        hideHintErrVolt(inputVoltageMaxOk)
        setActionButtonEnabled(WhichButton.POSITIVE, inputCurrentMaxOK && inputVoltageMaxOk && inputVoltageControlFileOk)
    }

    voltageMaxEditText.setText(configVoltage.max?.toString() ?: "", TextView.BufferType.EDITABLE) //Initial value

    enableVoltageLimitCheckBox.setOnCheckedChangeListener { _, isChecked ->

        if (isChecked.also { voltageMaxEditText.isEnabled = it })
        {
            if (!voltageMaxEditText.text.isNullOrEmpty()) checkVolt(voltageMaxEditText.text?.toString())
            voltageMaxEditText.hasFocusable()
        }
        else { hideHintErrVolt(true) ;  }
    }

    enableVoltageLimitCheckBox.isChecked = configVoltage.max != null
    voltageMaxEditText.isEnabled = configVoltage.max != null

    voltageMaxEditText.addTextChangedListener(object : TextWatcher
    {
        override fun afterTextChanged(s: Editable?) {}
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)
        {
            checkVolt(s.toString())
        }
    })

    //END -----------------------------------------------------------------------------------

    fun hideHintErrCurrent(hide: Boolean)
    {
        currentMaxEditText.error = if (hide) null else context.getString(R.string.invalid_current_max)
    }

    fun checkCurrent(value: String?)
    {
        // toIntOrNull: non-numeric or overflowing EditText input must invalidate
        // the field, never crash the dialog. Upper bound 9999 mirrors write-config.sh:
        // `acca --set --current >9999` is REJECTED by the daemon (no limit applied at
        // all) while the app would still report success, so cap it like voltage.
        inputCurrentMaxOK = (value?.toIntOrNull() ?: 0) in 1..9999
        hideHintErrCurrent(inputCurrentMaxOK)
        setActionButtonEnabled(WhichButton.POSITIVE, inputCurrentMaxOK && inputVoltageMaxOk && inputVoltageControlFileOk)
    }

    if(Acc.instance.version >= 202002170)
    {
        voltageControlFileLayout.visibility = View.GONE

        // Current-limit explainer. Static text always shows the input-vs-battery relationship
        // (measured: the limit caps CHARGER-INPUT current, and a 9V charger delivers ~1.8x that
        // into the battery, a 5V charger ~1x). When ACC reports live input telemetry AND the phone
        // is charging, replace it with the phone's OWN measured numbers -- naming the detected
        // charger tier (5V/9V/12V/15V/20V from the live input voltage) so the 5V-vs-9V difference
        // is explicit. Refreshed every few seconds while the dialog is open, so swapping the charger
        // updates it live (dynamic). Best-effort; any failure keeps the static note. Loop ends when
        // the dialog is dismissed (the coroutineScope is the editor activity's, cancelled on finish).
        val currentMaxHint = binding.currentMaxHintTv
        val refreshJob = coroutineScope.launch {
            while (isActive) {
                val st = try { Acc.instance.getState() } catch (e: Exception) { null }
                val vin = st?.inputVoltageMv; val iin = st?.inputCurrentMa
                val vbat = st?.voltageRaw?.let { if (it >= 100000L) (it / 1000L).toInt() else it.toInt() } ?: 0
                if (st != null && st.plugged && vin != null && vin > 0 && iin != null && iin > 50 && vbat in 3000..4600) {
                    // battery mA this input delivers now, and the setpoint for a 1000 mA battery target
                    val ratioX100 = (vin * 84) / vbat            // Vin/Vbat * 0.84 efficiency, x100
                    val battNow = iin * ratioX100 / 100
                    val setForTarget = if (ratioX100 > 0) 1000 * 100 / ratioX100 else 550
                    val ratioStr = String.format("%.1f", ratioX100 / 100.0)
                    val vinStr = String.format("%.1f", vin / 1000.0)
                    currentMaxHint.text = context.getString(
                        R.string.current_max_input_measured,
                        chargerTierLabel(context, vin), vinStr, ratioStr, iin, battNow, setForTarget
                    )
                } else {
                    // not charging / no telemetry -> keep the static rule visible
                    currentMaxHint.text = context.getString(R.string.current_max_input_note)
                }
                delay(3000)
            }
        }
        setOnDismissListener { refreshJob.cancel() }

        //CURRENT MAX SELECTION
        currentMaxEditText.setText(configCurrentMax?.toString() ?: "", TextView.BufferType.EDITABLE)

        enableCurrentLimitCheckBox.setOnCheckedChangeListener { _, isChecked ->

            if (isChecked.also { currentMaxEditText.isEnabled = it })
            {
                if (!currentMaxEditText.text.isNullOrEmpty()) checkCurrent(currentMaxEditText.text?.toString())
                currentMaxEditText.hasFocusable()
            }
            else hideHintErrCurrent(true)
        }

        enableCurrentLimitCheckBox.isChecked = configCurrentMax != null
        currentMaxEditText.isEnabled = configCurrentMax != null

        currentMaxEditText.addTextChangedListener(object : TextWatcher
        {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int)
            {
                checkCurrent(s.toString())
            }
        })
        //END

    } else {
        currentMaxLayout.visibility = View.GONE

        //Voltage control files are loaded asynchronously, do it now
        coroutineScope.launch {

            val supportedVoltageControlFiles = ArrayList(Acc.instance.listVoltageSupportedControlFiles())
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, supportedVoltageControlFiles)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            voltageControlSpinner.adapter = adapter

            //Load the selected item and set it
            configVoltage.controlFile?.let { currentVoltFile ->
                val currentVoltFileRegex = currentVoltFile.replace("/", """\/""").replace(".", """\.""").replace("?", ".").toRegex()
                val match = supportedVoltageControlFiles.find { currentVoltFileRegex.matches(it) }
                if(match == null) {
                    supportedVoltageControlFiles.add(currentVoltFile)
                    currentVoltFile
                } else match

            }?.let {
                voltageControlSpinner.setSelection(supportedVoltageControlFiles.indexOf(it))
            }

            //if no item is selected disable the button
            if(voltageControlSpinner.selectedItemPosition == -1)
            {
                inputVoltageControlFileOk = false
                setActionButtonEnabled(WhichButton.POSITIVE, false)
            }
            else {
                inputVoltageControlFileOk = true
                setActionButtonEnabled(WhichButton.POSITIVE, inputCurrentMaxOK && inputVoltageMaxOk && inputVoltageControlFileOk)
            }

            voltageControlSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
            {
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    inputVoltageControlFileOk = false
                    setActionButtonEnabled(WhichButton.POSITIVE, false)
                }

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
                {
                    val voltageMaxVal = voltageMaxEditText.text?.toString()?.toIntOrNull()
                    inputVoltageControlFileOk = voltageMaxVal != null && voltageMaxVal in 3700..4300
                    setActionButtonEnabled(WhichButton.POSITIVE, inputCurrentMaxOK && inputVoltageMaxOk && inputVoltageControlFileOk)
                }
            }
        }
    }

    return this
}

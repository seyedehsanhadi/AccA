package mattecarra.accapp.dialogs

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.SpinnerAdapter
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.customview.customView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.databinding.ScheduleDialogBinding
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.AccaProfile
import mattecarra.accapp.models.ProfileEnables
import mattecarra.accapp.models.Schedule
import mattecarra.accapp.utils.LogExt

class ProfileSpinnerAdapter : BaseAdapter(), SpinnerAdapter
{
    private var mList = emptyList<AccaProfile>()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View
    {
        val view =  convertView ?: LayoutInflater.from(parent.context).inflate(android.R.layout.simple_spinner_dropdown_item, parent, false)
        val textView = view.findViewById(android.R.id.text1) as TextView
        textView.text = getItem(position).profileName
        return view;
    }

    fun setItems(list: List<AccaProfile>) {
        if(mList != list) mList = list
        notifyDataSetChanged()
    }

    override fun getItem(position: Int): AccaProfile = mList[position]

    override fun getItemId(position: Int): Long = mList[position].uid.toLong()

    override fun getCount(): Int = mList.size

}

typealias AddScheduleListener = ((profileId: Long, scheduleName: String, time: String,
                                  executeOnce: Boolean, executeOnBoot: Boolean) -> Unit)

// readDefaultConfig() is a blocking root call. It used to be invoked via
// runBlocking{} on the MAIN thread while building the schedule dialog, which
// blocks the UI thread on a root shell -> ANR. Instead we seed the dialog with
// an in-memory AccConfig() immediately (no I/O) and load the real default
// asynchronously, then refresh the spinner adapter on the main thread.
private fun seedDefaultConfig(): AccConfig = AccConfig()

// Reads the real default config off the main thread; falls back to in-memory
// defaults if the root call throws so the dialog never crashes.
private suspend fun safeDefaultConfig(): AccConfig =
    try { withContext(Dispatchers.IO) { Acc.instance.readDefaultConfig() } }
    catch (e: Exception) { LogExt().d("ScheduleDialogExt", "readDefaultConfig failed: " + e.message); AccConfig() }

fun MaterialDialog.addScheduleDialog(
    profilesLiveData: LiveData<List<AccaProfile>>,
    profiles: MutableList<AccaProfile> = mutableListOf(
        AccaProfile(
            -1,
            context.getString(R.string.new_config),
            seedDefaultConfig(),
            ProfileEnables()
        )
    ),
    schedule: Schedule? = null,
    listener: AddScheduleListener
): MaterialDialog
{
    val adapter = ProfileSpinnerAdapter()
    val observer = Observer<List<AccaProfile>> {
        profiles.addAll(it)
        adapter.setItems(profiles)
    }

    val binding = ScheduleDialogBinding.inflate(layoutInflater)
    customView(view = binding.root)

    val spinner = binding.profileSelector
    val timePicker = binding.timePicker
    val timePickerContainer = binding.timePickerContainer
    val scheduleTypeSpinner = binding.scheduleTypeSelector
    val scheduleName = binding.scheduleNameEditText
    val executeOnBootCheckBox = binding.executeOnBootCheckbox

    positiveButton(R.string.save) { dialog ->

            // Read the schedule type LIVE at save time. It used to be captured at dialog BUILD
            // time, so changing boot/once/repeat in the spinner had NO effect -- every schedule
            // saved as the initial type. That is the core "scheduler doesn't work" bug.
            val scheduleType = scheduleTypeSpinner.selectedItemId

            val time = if(scheduleType == 2L) "boot" else
            {
                val hour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.hour else timePicker.currentHour
                val minute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.minute else timePicker.currentMinute
                "${String.format("%02d", hour)}${String.format("%02d", minute)}"
            }

            listener(spinner.selectedItemId, scheduleName.text.toString(), time, scheduleType == 1L, executeOnBootCheckBox.isChecked && scheduleType != 2L)
        }
        .onDismiss { profilesLiveData.removeObserver(observer) }

    profilesLiveData.observeForever(observer)
    spinner.adapter = adapter

    // Load the real default config off the main thread (avoids the old runBlocking ANR)
    // and patch the seeded in-memory placeholder profiles, then refresh the adapter.
    GlobalScope.launch(Dispatchers.Main) {
        val realDefault = safeDefaultConfig()
        profiles.filter { it.uid < 0 }.forEach { it.accConfig = realDefault }
        adapter.setItems(profiles)
    }

    scheduleTypeSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
    {
        override fun onNothingSelected(parent: AdapterView<*>?) {}
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long)
        {
            when(id) {
                 2L -> timePickerContainer.visibility = View.GONE
                else -> timePickerContainer.visibility = View.VISIBLE
            }
        }
    }

    timePicker.setIs24HourView(true)

    schedule?.let { schedule ->
        scheduleTypeSpinner.setSelection(
            when {
                schedule.isBootSchedule() -> 2
                schedule.executeOnce -> 1
                else -> 0
            }
        )

        schedule.getTime()?.let { (hour, minute) -> timePicker.currentHour = hour ; timePicker.currentMinute = minute }
        scheduleName.setText(schedule.profile.scheduleName)
        executeOnBootCheckBox.isChecked = schedule.executeOnBoot
    }

    return this
}

typealias EditScheduleListener =
        ((profileId: Long, profileName: String, time: String, executeOnce: Boolean, executeOnBoot: Boolean) -> Unit)


fun MaterialDialog.editScheduleDialog(
    schedule: Schedule,
    profilesLiveData: LiveData<List<AccaProfile>>,
    listener: EditScheduleListener
): MaterialDialog {
    return addScheduleDialog(
        profilesLiveData,
        // Seed with in-memory defaults (no blocking root call on the main thread);
        // addScheduleDialog() loads the real default config asynchronously and
        // patches every placeholder profile (uid < 0).
        seedDefaultConfig().let { defaultConfig ->
            mutableListOf(
                AccaProfile(-1, context.getString(R.string.schedule_profile_keep_current), defaultConfig, ProfileEnables()),
                AccaProfile(-2, context.getString(R.string.schedule_profile_edit_current), defaultConfig, ProfileEnables()),
                AccaProfile(-3, context.getString(R.string.new_config), defaultConfig, ProfileEnables()))
        },
        schedule,
        listener
    )
}
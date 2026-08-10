package mattecarra.accapp.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import mattecarra.accapp.R
import mattecarra.accapp.models.ScriptEntry

class ScriptEntryHolder(view: View) : RecyclerView.ViewHolder(view), ScriptEntry.Listener {
    private val mNameTv: TextView = view.findViewById(R.id.sex_item_name_tv)
    private val mPreviewTv: TextView = view.findViewById(R.id.sex_item_preview_tv)
    private val mCheckbox: CheckBox = view.findViewById(R.id.sex_item_checkbox)
    private lateinit var mEntry: ScriptEntry

    init {
        // Both the row and the checkbox must go through the MODEL. The layout marks the checkbox
        // non-clickable so taps fall through to the row, but a CompoundButton can still be toggled
        // by accessibility services, hardware keyboards and some launchers' tap injection -- and
        // that path changed only the tick, never mEntry, so getCheckedEntries() (which filters the
        // model) disagreed with what the user saw. Reported as "checked two, only one exported,
        // and the next export had the missing one concatenated". Wiring both to the same setter
        // means the view can never drift from the model.
        view.setOnClickListener { toggle() }
        mCheckbox.setOnClickListener { toggle() }
    }

    private fun toggle() {
        if (!this::mEntry.isInitialized) return
        mEntry.setIsChecked(!mEntry.isChecked())
    }

    fun setData(entry: ScriptEntry) {
        mEntry = entry
        mNameTv.text = entry.getName()
        mPreviewTv.text = entry.getPreview()
        mCheckbox.isChecked = entry.isChecked()
    }

    fun getEntry(): ScriptEntry = mEntry

    /** Safe on a holder that was recycled before it was ever bound. */
    fun clearListener() {
        if (this::mEntry.isInitialized) mEntry.setOnCheckedChangedListener(null)
    }

    override fun onCheckChanged(value: Boolean) {
        mCheckbox.isChecked = value
    }
}

/** Checkbox picker for exporting/importing scripts. Mirrors ProfileEntriesAdapter. */
class ScriptEntriesAdapter : RecyclerView.Adapter<ScriptEntryHolder>() {

    private val mEntries: MutableList<ScriptEntry> = ArrayList()

    @SuppressLint("NotifyDataSetChanged")
    fun addEntry(entry: ScriptEntry) {
        mEntries.add(entry)
        val position = itemCount - 1
        if (position == 0) notifyDataSetChanged() else notifyItemInserted(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptEntryHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.script_export_item, parent, false)
        return ScriptEntryHolder(view)
    }

    override fun onBindViewHolder(holder: ScriptEntryHolder, position: Int) {
        val entry = mEntries[position]
        entry.setOnCheckedChangedListener(holder)
        holder.setData(entry)
    }

    override fun onViewRecycled(holder: ScriptEntryHolder) {
        // getEntry() reads a lateinit field: a holder recycled before it was ever bound throws
        // UninitializedPropertyAccessException here, which aborts the recycle and can leave an
        // entry still pointing at a holder that has moved on -- the drift above, by another route.
        holder.clearListener()
    }

    override fun getItemCount(): Int = mEntries.size

    fun getCheckedEntries(): List<ScriptEntry> = mEntries.filter { it.isChecked() }

    /** Nothing selected is the commonest first action, so default the picker to everything on. */
    fun uncheckAll() {
        for (entry in mEntries) if (entry.isChecked()) entry.setIsChecked(false)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun checkAll() {
        for (entry in mEntries) if (!entry.isChecked()) entry.setIsChecked(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearEntries() {
        mEntries.clear()
        notifyDataSetChanged()
    }
}

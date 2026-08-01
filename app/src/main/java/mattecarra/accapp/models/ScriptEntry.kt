package mattecarra.accapp.models

import java.io.Serializable

/**
 * A script wrapped for the export/import picker, mirroring [ProfileEntry].
 *
 * Scripts had no export path at all: they lived only in the Room database, so uninstalling or
 * reinstalling the app took every script the user had written with it, with no way to get them
 * out first. This is the transport type for the same checkbox picker profiles already use.
 *
 * The whole script is carried, including scOrder, so a list exported now still restores in the
 * user's arrangement rather than an arbitrary one.
 */
data class ScriptEntry(var script: AccaScript): Serializable {

    private val mName: String = script.scName

    @Transient private var mListener: Listener? = null
    private var mIsChecked: Boolean = false

    fun setOnCheckedChangedListener(listener: Listener?) {
        mListener = listener
    }

    fun isChecked(): Boolean {
        return mIsChecked
    }

    fun getName(): String {
        return mName
    }

    /** One-line preview for the picker, so two scripts with similar names stay distinguishable. */
    fun getPreview(): String {
        val body = script.scBody.trim().replace(Regex("\\s+"), " ")
        return if (body.length > 60) body.take(60) + "..." else body
    }

    fun setIsChecked(isChecked: Boolean) {
        mIsChecked = isChecked

        mListener?.onCheckChanged(mIsChecked)
    }

    interface Listener {
        fun onCheckChanged(value: Boolean)
    }
}

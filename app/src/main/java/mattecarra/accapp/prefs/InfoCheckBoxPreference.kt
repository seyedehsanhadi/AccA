package mattecarra.accapp.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceViewHolder
import com.afollestad.materialdialogs.MaterialDialog
import mattecarra.accapp.R

/**
 * A CheckBoxPreference that keeps its inline summary short and puts the full explanation behind a
 * tappable info (i) icon in the widget slot. Tapping the icon opens a dialog; tapping the rest of
 * the row still toggles the checkbox as usual. Pass the long text via app:infoText.
 */
class InfoCheckBoxPreference(context: Context, attrs: AttributeSet?) :
    CheckBoxPreference(context, attrs) {

    private val infoText: CharSequence?

    init {
        widgetLayoutResource = R.layout.pref_widget_checkbox_info
        val a = context.obtainStyledAttributes(attrs, R.styleable.InfoCheckBoxPreference)
        infoText = a.getText(R.styleable.InfoCheckBoxPreference_infoText)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.pref_info) as? ImageView)?.setOnClickListener { v ->
            MaterialDialog(v.context).show {
                title(text = this@InfoCheckBoxPreference.title?.toString())
                message(text = infoText?.toString())
                positiveButton(R.string.close)
            }
        }
    }
}

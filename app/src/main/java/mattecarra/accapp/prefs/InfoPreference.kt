package mattecarra.accapp.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.afollestad.materialdialogs.MaterialDialog
import mattecarra.accapp.R

/**
 * A plain Preference that keeps its inline summary short and puts the full explanation behind a
 * tappable info (i) icon. Same idea as InfoCheckBoxPreference, without a checkbox. Pass the long
 * text via app:infoText.
 */
class InfoPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    private val infoText: CharSequence?

    init {
        widgetLayoutResource = R.layout.pref_widget_info
        val a = context.obtainStyledAttributes(attrs, R.styleable.InfoCheckBoxPreference)
        infoText = a.getText(R.styleable.InfoCheckBoxPreference_infoText)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.pref_info) as? ImageView)?.setOnClickListener { v ->
            MaterialDialog(v.context).show {
                title(text = this@InfoPreference.title?.toString())
                message(text = infoText?.toString())
                positiveButton(R.string.close)
            }
        }
    }
}

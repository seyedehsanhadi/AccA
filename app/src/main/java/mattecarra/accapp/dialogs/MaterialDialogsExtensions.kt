package mattecarra.accapp.dialogs

import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.utils.GithubUtils
import java.io.File

    @CheckResult
    fun MaterialDialog.shareLogsNeutralButton(file: File, extraTextRes: Int): MaterialDialog
    {
        return neutralButton(R.string.share) {

            if(file.exists())
            {
                val intentShareFile = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this.context.applicationContext, "mattecarra.accapp.fileprovider", file))
                    .putExtra(Intent.EXTRA_TEXT, context.getString(extraTextRes))

                context.startActivity(Intent.createChooser(intentShareFile, context.getString(R.string.share_log)))

            } else {
                Toast.makeText(context, R.string.logs_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    /*
    * Return true to normally process the event
    * Return false to cancel the event and add your own logic
    * */
    typealias KeyCodeBackListener = (() -> Boolean)

    @CheckResult
    fun MaterialDialog.onKeyCodeBackPressed(callback: KeyCodeBackListener): MaterialDialog
    {
        setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) { callback() } else true
        }
        return this
    }

    typealias VersionChoiceListener = ((version: String) -> Unit)

    @CheckResult
    suspend fun MaterialDialog.accVersionSingleChoice(
        accVersion: String, callback: VersionChoiceListener): MaterialDialog
    {
        val options = context.resources.getStringArray(R.array.acc_version_options).toMutableList()
        val optionValues = context.resources.getStringArray(R.array.acc_version_option_values).toMutableList()
        val githubTags = GithubUtils.listAccReleaseTags(Preferences(context).includePreReleases)
        options.addAll(githubTags)

        // Default to the LATEST GitHub release, not the bundled fallback: the bundle ships a fixed
        // (often older) ACC, and ACC/AccA are released independently, so the offer should track
        // GitHub. Bundled stays in the list as an offline fallback; a version the user pinned
        // before is still honored.
        val storedIndex =
            if (optionValues.contains(accVersion)) optionValues.indexOf(accVersion)
            else options.map { it.toLowerCase() }.indexOf(accVersion)
        val latestGithubIndex = if (githubTags.isNotEmpty()) optionValues.size else -1
        val initial = when {
            storedIndex >= 0 && accVersion != "bundled" -> storedIndex
            latestGithubIndex >= 0 -> latestGithubIndex
            storedIndex >= 0 -> storedIndex
            else -> 0
        }

        return listItemsSingleChoice(
            items = options,
            initialSelection = initial
        ) { _, index, text ->
            if(index in optionValues.indices) { callback(optionValues[index]) }
            else { callback(text.toString().toLowerCase()) }
        }
    }
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
        // The value array (["bundled"]) is NOT localized and is the source of truth for how many
        // fixed rows exist. The localized DISPLAY arrays still ship stale extra rows (Master /
        // Develop) with no matching value; truncate the labels to the value array so display and
        // values line up 1:1 in EVERY locale (else the "latest" default and tag->ref mapping break
        // in non-English builds).
        val fixedValues = context.resources.getStringArray(R.array.acc_version_option_values).toList()
        val fixedLabels = context.resources.getStringArray(R.array.acc_version_options).toList().take(fixedValues.size)
        val githubTags = GithubUtils.listAccReleaseTags(Preferences(context).includePreReleases)
        val options = fixedLabels + githubTags

        // Value for a row: the fixed value for the first rows, then the EXACT git tag for the rest.
        // Never lowercase a tag -- a lowercased/translated ref 404s on the github archive URL.
        fun valueAt(i: Int) = if (i < fixedValues.size) fixedValues[i] else githubTags[i - fixedValues.size]

        // Default to the LATEST GitHub release (first tag), not the bundled fallback: the bundle
        // ships a fixed (often older) ACC and ACC/AccA release independently. Bundled stays as an
        // offline fallback; a version the user pinned before is still honored.
        val storedIndex = options.indices.firstOrNull { valueAt(it) == accVersion } ?: -1
        val latestGithubIndex = if (githubTags.isNotEmpty()) fixedValues.size else -1
        val initial = when {
            storedIndex >= 0 && accVersion != "bundled" -> storedIndex
            latestGithubIndex >= 0 -> latestGithubIndex
            storedIndex >= 0 -> storedIndex
            else -> 0
        }

        return listItemsSingleChoice(
            items = options,
            initialSelection = initial
        ) { _, index, _ ->
            callback(valueAt(index))
        }
    }
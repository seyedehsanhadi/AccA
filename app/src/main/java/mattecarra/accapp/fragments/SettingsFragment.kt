package mattecarra.accapp.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.dialogs.*
import mattecarra.accapp.utils.Constants.ACC_VERSION
import mattecarra.accapp.djs.Djs
import mattecarra.accapp.utils.Constants.DJS_ENABLED
import mattecarra.accapp.utils.GithubUtils
import mattecarra.accapp.utils.LogExt
import java.io.File
import kotlin.coroutines.CoroutineContext

class SettingsFragment : PreferenceFragmentCompat(), CoroutineScope {
    companion object {
	    fun newInstance() = SettingsFragment()
    }

    protected lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    @SuppressLint("DefaultLocale")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
    {
        LogExt().d(javaClass.simpleName, "onCreatePreferences()")

        setPreferencesFromResource(R.xml.settings, rootKey)

        val telegram = findPreference<Preference>("acc_telegram")
        telegram?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            context?.let {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+hU1oF-BCf5hmM2Rk")))
                } catch (ignored: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+hU1oF-BCf5hmM2Rk")))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            true
        }

        // ui_refresh: how often the daemon republishes state.json, the snapshot this app reads for
        // the charge meter. Display-only -- no charging decision reads that file -- so the whole
        // cost of raising it is that the meter's live current updates less often. Level and status
        // changes still publish within 5s at every value.
        //
        // Re-measured with each phone held awake by a partial wakelock, so every arm spends the
        // same fraction of the window running and only the setting differs. Without that pin the
        // numbers are meaningless: an A3 ran the same sleep in 496s of wall in one arm and 586s in
        // another, and the arm that slept least looked most expensive - which produced a result
        // saying that publishing LESS cost 44% MORE.
        //
        // Per minute of awake time, with a repeated 60s control arm giving the noise floor
        // (0.5% Pixel 6a, 1.4% A3):
        //   Pixel 6a:  60s = 3334/3350,  120s = 3190 (-4%),  off = 2746 (-18%)
        //   Mi A3:     60s = 5980/5896,  120s = 6093 (nil),  off = 5661 (-5%)
        // Only "off" reliably buys anything. The rest of the idle cost is the daemon loop, not
        // this publish, so the entry labels say the direction rather than a fraction.
        //
        // Written straight through `acc -s`, not through the config editor, so it cannot disturb a
        // profile the user has built. A failure is reported rather than silently swallowed: the
        // preference only keeps the new value if ACC accepted it.
        //
        // The screen must SHOW the setting, not hide it behind a tap. Reported: "it is not known
        // which one is selected, you have to open the pop-up to find out". Two things were wrong.
        // The summary only ever got the selected entry written into it AFTER a change, so a fresh
        // install or a restart showed the generic explanation and no value at all. And the stored
        // preference was never reconciled with ACC, so if the two disagreed -- a value set from
        // the command line, a config restored from a backup -- the dialog opened with no radio
        // button marked, because the stored value matched none of the entries.
        //
        // Read the live value out of ACC's own config, make the preference agree with it, and put
        // it in the summary. ACC is the authority here; the preference is a mirror.
        findPreference<ListPreference>("ui_refresh")?.let { pref ->
            val live = Shell.su("sed -n 's/^uiRefresh=//p' /data/adb/vr25/acc-data/config.txt")
                .exec().out.firstOrNull()?.trim()
            if (!live.isNullOrEmpty() && pref.findIndexOfValue(live) >= 0) pref.value = live
            showUiRefresh(pref)

            pref.setOnPreferenceChangeListener { p, newValue ->
                val v = newValue as String
                // Absolute path, like every other call in this app. Bare "acc" does resolve on the
                // root managers tested here (KernelSU adds /data/adb/ksu/bin, Magisk has
                // /system/bin/acc), but it is an assumption about PATH that nothing else makes.
                val ok = Shell.su("/dev/.vr25/acc/acca -s ui_refresh=$v").exec().isSuccess
                if (ok) {
                    (p as ListPreference).value = v
                    showUiRefresh(p)
                } else {
                    Toast.makeText(context, R.string.ui_refresh_failed, Toast.LENGTH_LONG).show()
                }
                ok
            }
        }

        // Selected value first, explanation after, so the answer to "what is it set to" is the
        // first thing on the line rather than the last.
        val theme = findPreference<ListPreference>("theme")
        theme?.setOnPreferenceChangeListener { _, newValue ->
            when (newValue as String) {
                "0" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "1" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "2" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            true
        }

        val accVersion = findPreference<Preference>(ACC_VERSION)
        accVersion?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            context?.let { ctx ->
                // AccA does NOT install or bundle ACC. This checks the installed ACC against the
                // latest release on GitHub (the fork's module.json - the same file Magisk reads) and,
                // when ACC is missing or a newer version exists, points the user at the release to
                // download and flash themselves. Discoverable "update ACC from AccA" without AccA
                // ever overriding a flashed module.
                this@SettingsFragment.launch {
                    val installedStr = try { Acc.getAccVersionToStr().trim() } catch (e: Exception) { "" }
                    val installedCode = Regex("\\((\\d+)\\)").find(installedStr)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val includePre = Preferences(ctx).includePreReleases

                    val checking = MaterialDialog(ctx).show {
                        title(R.string.acc_checking_update)
                        progress(R.string.wait)
                        cancelOnTouchOutside(false)
                    }
                    // Full list of ACC releases from GitHub, like AccA's own version list. AccA never
                    // installs ACC; tapping a version opens its release (download the zip, flash it in
                    // Magisk/KernelSU/APatch). Codes come from the zip asset name (acc_<ver>_<code>...).
                    val releases = GithubUtils.listAccReleases(includePre)
                    if (!isAdded || activity?.isFinishing != false) { try { checking.dismiss() } catch (_: Exception) {}; return@launch }
                    try { checking.dismiss() } catch (_: Exception) {}

                    fun open(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
                    fun codeOf(r: GithubUtils.ReleaseEntry) = Regex("(20\\d{7})").find(r.downloadUrl ?: "")?.value?.toIntOrNull()

                    if (releases.isEmpty()) {
                        MaterialDialog(ctx).show {
                            title(R.string.acc_module_title)
                            message(text = if (installedStr.isBlank()) getString(R.string.acc_get_message)
                                           else getString(R.string.acc_installed_cant_check, installedStr))
                            positiveButton(android.R.string.ok)
                            neutralButton(R.string.get_acc) { open("https://github.com/seyedehsanhadi/acc/releases/latest") }
                        }
                        return@launch
                    }

                    val latestCode = releases.firstNotNullOfOrNull { codeOf(it) }
                    val updateHint = if (installedCode != null && latestCode != null && latestCode > installedCode)
                        "  - update available" else ""
                    val labels = releases.map { r ->
                        buildString {
                            append(r.tag)
                            if (r.prerelease) append("  (pre-release)")
                            if (installedCode != null && codeOf(r) == installedCode) append("  ✓ installed")
                        }
                    }
                    MaterialDialog(ctx).show {
                        title(text = getString(R.string.acc_module_title) +
                            (if (installedStr.isNotBlank()) "  (installed: $installedStr)" else "") + updateHint)
                        listItems(items = labels) { _, index, _ ->
                            val r = releases[index]
                            open(r.downloadUrl?.takeIf { it.isNotBlank() } ?: r.pageUrl)
                        }
                        negativeButton(android.R.string.cancel)
                    }
                }
            }

            true
        }

        val djsEnable = findPreference<CheckBoxPreference>(DJS_ENABLED)
        djsEnable?.setOnPreferenceChangeListener { _, isEnabled ->
            context?.let { context ->
                when {
                    // Enabling: isDjsInstalled/initDjs are blocking root-shell calls -> never run
                    // them on the main thread. Defer the checkbox state (return false), do the
                    // check/init off-main, then apply the result on Main.
                    isEnabled as Boolean -> {
                        launch {
                            val installed = try {
                                withContext(Dispatchers.IO) { Djs.isDjsInstalled(context.filesDir) }
                            } catch (e: Exception) {
                                LogExt().e(javaClass.simpleName, "isDjsInstalled failed: ${e.message}")
                                false
                            }

                            if (installed) {
                                try {
                                    withContext(Dispatchers.IO) { Djs.initDjs(context.filesDir) }
                                } catch (e: Exception) {
                                    LogExt().e(javaClass.simpleName, "initDjs failed: ${e.message}")
                                }
                                if (isAdded) djsEnable.isChecked = true
                                return@launch
                            }

                            // Not installed yet: show the install dialog (Main thread).
                            if (!isAdded || activity?.isFinishing != false) return@launch

                            val showInstall = {
                                MaterialDialog(context).show {
                                title(R.string.installing_djs)
                                cancelOnTouchOutside(false)
                                onKeyCodeBackPressed { false }
                                djsInstallation(this@SettingsFragment, object: DjsInstallationListener {
                                    override fun onInstallationFailed(result: Shell.Result?) {
                                    MaterialDialog(context) //Other installation errors can not be handled automatically -> show a dialog with the logs
                                        .show {
                                            title(R.string.djs_installation_failed_title)
                                            message(R.string.djs_installation_failed)
                                            positiveButton(android.R.string.ok)
                                            if(result != null)
                                                shareLogsNeutralButton(File(context.filesDir, "logs/djs-install.log"), R.string.djs_installation_failed_log)
                                        }
                                }

                                override fun onBusyboxMissing() {
                                    MaterialDialog(context)
                                        .show {
                                            title(R.string.installation_failed_busybox_title)
                                            message(R.string.installation_failed_busybox)
                                            positiveButton(android.R.string.ok)
                                            cancelOnTouchOutside(false)
                                        }
                                }

                                override fun onSuccess() {
                                    djsEnable.isChecked = true
                                }

                                })
                                }
                            }

                            val onMagisk = try {
                                withContext(Dispatchers.IO) { Shell.su("test -d /data/adb/magisk").exec().isSuccess }
                            } catch (e: Exception) { true }

                            if (onMagisk) {
                                showInstall()
                            } else {
                                MaterialDialog(context).show {
                                    title(R.string.djs_ksu_warning_title)
                                    message(R.string.djs_ksu_warning_message)
                                    positiveButton(R.string.djs_ksu_warning_continue) { showInstall() }
                                    negativeButton(android.R.string.cancel)
                                }
                            }
                        }

                        // Checkbox state is applied asynchronously (onSuccess / installed path);
                        // don't flip it now.
                        false
                    }

                    else -> {
                        launch {
                            Djs.uninstallDjs(context.filesDir)
                        }

                        true
                    }
                }
            } ?: false
        }

        if(Acc.instance.version >= 202002290) {
            findPreference<Preference>("current_measure_unit")?.isEnabled = false
            findPreference<Preference>("voltage_measure_unit")?.isEnabled = false
        }

        // Status-bar charge meter (rc15): start/stop the read-only meter service to match the
        // toggle, and re-render on a display/active change. On Android 13+ enabling it needs the
        // POST_NOTIFICATIONS runtime grant, or the ongoing notification is silently suppressed.
        findPreference<CheckBoxPreference>("charge_meter_enabled")?.setOnPreferenceChangeListener { _, v ->
            val enable = v as Boolean
            context?.let { ctx ->
                // POST_NOTIFICATIONS is API 33 (TIRAMISU); referenced by raw value + string so this
                // still compiles against compileSdk 31.
                val postNotif = "android.permission.POST_NOTIFICATIONS"
                if (enable && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, postNotif)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { requestPermissions(arrayOf(postNotif), 4712) } catch (_: Exception) {}
                }
                // The pref value is written after this listener returns true; post the sync so it
                // reads the new value.
                view?.post { mattecarra.accapp.services.ChargeMeterService.sync(ctx) }
                    ?: mattecarra.accapp.services.ChargeMeterService.sync(ctx)
            }
            true
        }
        val meterResync = Preference.OnPreferenceChangeListener { _, _ ->
            context?.let { ctx -> view?.post { mattecarra.accapp.services.ChargeMeterService.sync(ctx) } }
            true
        }
        findPreference<ListPreference>("charge_meter_display")?.onPreferenceChangeListener = meterResync
        findPreference<ListPreference>("charge_meter_style")?.onPreferenceChangeListener = meterResync
        findPreference<ListPreference>("charge_meter_battery_source")?.onPreferenceChangeListener = meterResync

        // "Check for updates" is AccA-only. ACC already surfaces its own update via module.prop's
        // updateJson, which Magisk/KernelSU show natively in their Modules list - no duplicate
        // nagging needed here. This lists every AccA release straight from GitHub in GitHub's own
        // (newest-first) order; tapping one downloads its APK directly.
        findPreference<Preference>("check_updates")?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val ctx = context ?: return@OnPreferenceClickListener true
            val includePre = Preferences(ctx).includePreReleases
            val progress = MaterialDialog(ctx).show {
                title(R.string.check_updates_title)
                message(R.string.check_updates_checking)
                cancelOnTouchOutside(false)
            }
            launch {
                val releases = GithubUtils.listAccaReleases(includePre)
                try { progress.dismiss() } catch (_: Exception) {}
                if (!isAdded || activity?.isFinishing != false) return@launch

                if (releases.isEmpty()) {
                    MaterialDialog(ctx).show {
                        title(R.string.check_updates_title)
                        message(R.string.check_updates_none)
                        positiveButton(android.R.string.ok)
                    }
                    return@launch
                }

                val installed = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
                fun norm(s: String) = s.trim().trimStart('v', 'V')
                val labels = releases.map {
                    buildString {
                        append(it.tag)
                        if (it.prerelease) append("  (pre-release)")
                        if (installed.isNotBlank() && norm(it.tag) == norm(installed)) append("  ✓ installed")
                    }
                }
                MaterialDialog(ctx).show {
                    title(text = getString(R.string.check_updates_title) +
                        if (installed.isNotBlank()) "  (installed: $installed)" else "")
                    listItems(items = labels) { _, index, _ ->
                        val r = releases[index]
                        val target = r.downloadUrl?.takeIf { it.isNotBlank() } ?: r.pageUrl
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    }
                    negativeButton(android.R.string.cancel)
                }
            }
            true
        }
    }

    private fun showUiRefresh(pref: ListPreference) {
        val i = pref.findIndexOfValue(pref.value)
        val label = if (i >= 0) pref.entries[i] else pref.value ?: ""
        pref.summary = getString(R.string.ui_refresh_pref_summary_selected, label) +
            System.lineSeparator() + getString(R.string.ui_refresh_pref_summary)
    }
}
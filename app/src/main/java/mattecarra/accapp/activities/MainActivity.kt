package mattecarra.accapp.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.databinding.ActivityMainBinding
import mattecarra.accapp.dialogs.*
import mattecarra.accapp.djs.Djs
import mattecarra.accapp.fragments.*
import mattecarra.accapp.models.*
import mattecarra.accapp.utils.*
import mattecarra.accapp.viewmodel.ProfilesViewModel
import mattecarra.accapp.viewmodel.SchedulesViewModel
import mattecarra.accapp.viewmodel.SharedViewModel
import xml.BatteryInfoWidget
import xml.WIDGET_ALL_UPDATE
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : ScopedAppActivity(), BottomNavigationView.OnNavigationItemSelectedListener
{
    private val LOG_TAG = "MainActivity"
    val ACC_CONFIG_EDITOR_REQUEST = 1
    val ACC_PROFILE_CREATOR_REQUEST = 2
    val ACC_PROFILE_EDITOR_REQUEST = 3
    val ACC_ADD_PROFILE_SCHEDULER_REQUEST = 4
    val ACC_EDIT_PROFILE_SCHEDULER_REQUEST = 5
    val ACC_IMPORT_PROFILE_REQUEST = 6

    private lateinit var binding: ActivityMainBinding
    private lateinit var _preferences: Preferences
    private lateinit var _sharedViewModel: SharedViewModel
    private lateinit var _schedulesViewModel: SchedulesViewModel
    private lateinit var _profilesViewModel: ProfilesViewModel

    // initUi() now runs after an async ACC detect, so the toolbar/bottom-nav can exist before
    // the (lateinit) ViewModels do. Guards below ignore nav/menu taps until init completes,
    // preventing UninitializedPropertyAccessException during that window.
    @Volatile private var isUiInitialized = false

    val mainFragment = DashboardFragment.newInstance()
    val profilesFragment = ProfilesFragment.newInstance()
    val scriptsFragment = ScriptesFragment.newInstance()
    val schedulesFragment = SchedulesFragment.newInstance()

    var selectedNavBarItem = R.id.botNav_home

    private fun initUi()
    {
        // Assign ViewModel
        _sharedViewModel = ViewModelProvider(this).get(SharedViewModel::class.java)
        _profilesViewModel = ViewModelProvider(this).get(ProfilesViewModel::class.java)
        _schedulesViewModel = ViewModelProvider(this).get(SchedulesViewModel::class.java)
        isUiInitialized = true   // ViewModels exist -> nav/menu handlers are safe now

        // Subscribe to viewmodel config and action if config is null
        _sharedViewModel.observeConfig(this, Observer { r ->
            if (r.first == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.parse_failed_title)
                    .setMessage(getString(R.string.parse_failed_body, r.second))
                    .setPositiveButton("Load Default") {dialog, _ ->
                        _sharedViewModel.loadDefaultConfig()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Exit") {dialog, _ ->
                        dialog.dismiss()
                        finish()
                    }
                    .setNeutralButton("Share") {dialog, _ ->
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, r.second)
                            type = "text/plain"
                        }

                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    .show()
            }
        })

        // Set Bottom Navigation Bar Item Selected Listener
        binding.mainBottomNav.setOnNavigationItemSelectedListener(this)
        setSupportActionBar(binding.mainToolbar)

        // Load in dashboard fragment
        binding.mainBottomNav.selectedItemId = selectedNavBarItem
    }

    /**
     * Function for handing navigation bar clicks
     */
    override fun onNavigationItemSelected(m: MenuItem): Boolean {
        if (!isUiInitialized) return false   // ignore taps until ViewModels/UI are ready
        // Record currently selected navigation item
        selectedNavBarItem = m.itemId

        when (m.itemId) {
            R.id.botNav_home -> {
                loadFragment(mainFragment)
                return true
            }
            R.id.botNav_profiles -> {
                loadFragment(profilesFragment)
                return true
            }
            R.id.botNav_scriptes -> {
                loadFragment(scriptsFragment)
                return true
            }
            R.id.botNav_schedules -> {
                // Djs.isDjsInstalled / initDjs / isInstalledDjsOutdated do blocking root shell
                // work -> doing them inline on this nav callback froze the UI (ANR). Run them
                // off the main thread, then navigate / show the dialog back on Main. We don't
                // commit the nav selection synchronously here (return false) -- the async branch
                // either loads the Schedules fragment or pops the install dialog.
                launch {
                    // state: 0 = ready (load schedules), 1 = not installed (ask first),
                    // 2 = installed but missing/outdated (install directly), like the old branches.
                    val state = try {
                        withContext(Dispatchers.IO) {
                            when {
                                !Djs.isDjsInstalled(filesDir) -> 1
                                !Djs.initDjs(filesDir) || Djs.isInstalledDjsOutdated() -> 2
                                else -> 0
                            }
                        }
                    } catch (e: Exception) {
                        LogExt().e(javaClass.simpleName, "DJS check failed: ${e.message}")
                        1   // fall through to the install dialog on any failure
                    }
                    if (isFinishing || isDestroyed) return@launch
                    when (state) {
                        0 -> loadFragment(schedulesFragment)
                        2 -> installDjs()
                        else -> djsInstallationDialog()
                    }
                }
                return false
            }
        }

        return false
    }

    fun djsInstallationDialog()
    {
        MaterialDialog(this).show {
            title(R.string.install_djs_title)
            message(R.string.install_djs_description)
            positiveButton(R.string.install) { installDjs() }
            negativeButton(android.R.string.no)
        }
    }

    fun installDjs()
    {
        MaterialDialog(this@MainActivity).show {

            title(R.string.installing_djs)
            cancelOnTouchOutside(false)
            onKeyCodeBackPressed { false }

            djsInstallation(this@MainActivity, object : DjsInstallationListener
            {
                override fun onInstallationFailed(result: Shell.Result?)
                {
                    MaterialDialog(this@MainActivity)
                        .show {
                            title(R.string.djs_installation_failed_title)
                            message(R.string.djs_installation_failed)
                            positiveButton(R.string.retry) { installDjs() }
                            negativeButton(android.R.string.cancel) { binding.mainBottomNav.selectedItemId = R.id.botNav_schedules }
                            if (result != null) shareLogsNeutralButton(File(filesDir, "logs/djs-install.log"), R.string.djs_installation_failed_log)
                        }
                }

                override fun onBusyboxMissing()
                {
                    MaterialDialog(this@MainActivity).show {
                            busyBoxError()
                            positiveButton(R.string.retry) { installDjs() }
                            negativeButton(android.R.string.cancel) { binding.mainBottomNav.selectedItemId = R.id.botNav_schedules }
                            cancelOnTouchOutside(false)
                        }
                }

                override fun onSuccess()
                {
                    _preferences.djsEnabled = true
                    initUi()
                }
            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
    {
        R.id.menu_appbar_logs -> {
            startActivity(Intent(this, LogViewerActivity::class.java))
            true
        }
        R.id.menu_appbar_settings -> {
            SettingsActivity.launch(this)
            true
        }
        R.id.menu_appbar_about -> {
            AboutActivity.launch(this)
            true
        }
        R.id.menu_appbar_import -> {
            startActivityForResult(Intent(this, ImportProfilesActivity::class.java), ACC_IMPORT_PROFILE_REQUEST)
            true
        }

        R.id.menu_appbar_export ->
        {
            if (isUiInitialized) {   // _profilesViewModel may not exist yet during async init
                // Generate list of ExportEntries TODO: maybe move this to the actual activity to make new ProfileEntries from AccaProfiles
                var profileList: ArrayList<ProfileEntry> = ArrayList()
                launch {
                    for (profile: AccaProfile in _profilesViewModel.getProfiles()) {
                        profileList.add(ProfileEntry(profile))
                    }
                }.invokeOnCompletion {
                    var intent = Intent(this, ExportProfilesActivity::class.java).putExtra("list", profileList)
                    startActivity(intent)
                }
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadFragment(fragment: Fragment)
    {
        // Never commit after onSaveInstanceState (e.g. tapping nav as the app backgrounds)
        // -> that throws IllegalStateException. commitAllowingStateLoss is safe here because
        // the selected tab is restored from selectedNavBarItem on recreate.
        if (supportFragmentManager.isStateSaved) {
            supportFragmentManager.beginTransaction().replace(R.id.main_framelayout, fragment).commitAllowingStateLoss()
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.main_framelayout, fragment)
        transaction.commit()
    }

    /**
     * Function for Status Card Settings OnClick (Configuration)
     */
    fun batteryConfigOnClick(view: View)
    {
        Intent(view.context, AccConfigEditorActivity::class.java).also { intent ->
            startActivityForResult(intent, ACC_CONFIG_EDITOR_REQUEST) }
    }

    /**
     * Function for launching the profile creation Activity
     */
    fun accProfilesFabOnClick(view: View)
    {
        launch {
            try {
                // readDefaultConfig() does blocking root work -> off the main thread.
                val defaultConfig = withContext(Dispatchers.IO) { Acc.instance.readDefaultConfig() }
                if (isFinishing || isDestroyed) return@launch
                Intent(this@MainActivity, AccConfigEditorActivity::class.java).also { intent ->
                    intent.putExtra(Constants.TITLE_KEY, this@MainActivity.getString(R.string.profile_creator))
                    intent.putExtra(Constants.ACC_CONFIG_KEY, defaultConfig)
                    startActivityForResult(intent, ACC_PROFILE_CREATOR_REQUEST)
                }
            } catch (e: Exception) {
                LogExt().e(javaClass.simpleName, "Profile creator launch failed: ${e.message}")
            }
        }
    }

    /**
     * Detect ACC OFF the main thread, then init the UI on the main thread (or show the
     * "ACC not found" dialog). The FIRST Shell.su spawns the root shell / shows the root
     * prompt; doing that synchronously in onCreate froze the UI to a blank/white screen
     * (ANR). Here the blocking root work runs on Dispatchers.IO and pre-warms Acc.instance
     * so initUi() never blocks the main thread. Everything is guarded so a failure shows a
     * message instead of a permanent blank page.
     */
    private fun detectAccAndInit() {
        launch {
            // ALL blocking root work runs off the main thread: Shell.rootAccess() (the first
            // call spawns the su daemon / shows the root prompt) AND Acc/Djs detection +
            // instance warm-up (their getters do blocking Shell.su). Doing any of these on
            // the main thread froze the UI to a blank/white screen (ANR) -- the reported bug.
            val state = withContext(Dispatchers.IO) {
                try {
                    if (!Shell.rootAccess()) return@withContext "noroot"
                    val ok = Acc.isAccInstalled(filesDir)
                    if (ok) {
                        Acc.instance   // warm ACC off-main
                        // DJS has the SAME blocking-shell getter; warm it too so the
                        // Schedules tab (SchedulesViewModel -> Djs.instance) never blocks main.
                        try { if (Djs.isDjsInstalled(filesDir)) Djs.instance } catch (_: Exception) {}
                    }
                    if (ok) "ok" else "noacc"
                } catch (e: Exception) {
                    LogExt().e(javaClass.simpleName, "ACC detection failed: ${e.message}"); "noacc"
                }
            }
            if (isFinishing) return@launch
            when (state) {
                "noroot" -> showNoRoot()
                "ok" -> {
                    try { initUi() }
                    catch (e: Exception) {
                        LogExt().e(javaClass.simpleName, "initUi failed: ${e.message}")
                        if (!isFinishing) MaterialDialog(this@MainActivity).show {
                            title(R.string.acc_installation_failed_title)
                            message(R.string.acc_installation_failed)
                            positiveButton(R.string.retry) { detectAccAndInit() }
                            neutralButton(R.string.exit) { finish() }
                            cancelOnTouchOutside(false)
                        }
                    }
                }
                else -> showAccNotFound()
            }
        }
    }

    private fun showNoRoot() {
        if (isFinishing) return
        MaterialDialog(this).show {
            title(R.string.tile_acc_no_root)
            message(R.string.no_root_message)
            positiveButton(android.R.string.ok) { finish() }
            cancelOnTouchOutside(false)
            onKeyCodeBackPressed { dismiss(); finish(); false }
        }
    }

    // AccA controls the ACC module but no longer installs it; the user flashes ACC
    // separately. If it is missing, point them at the zip and let them retry.
    private fun showAccNotFound() {
        if (isFinishing) return
        MaterialDialog(this).show {
            title(R.string.acc_not_installed_title)
            message(R.string.acc_not_installed_message)
            positiveButton(R.string.get_acc) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.ACC_RELEASE_URL)))
                } catch (_: Exception) { }
            }
            negativeButton(R.string.retry) { detectAccAndInit() }
            neutralButton(R.string.exit) { finish() }
            cancelOnTouchOutside(false)
            onKeyCodeBackPressed {
                finish()
                false
            }
        }
    }

    /*
    * This method should only be called when version = master | dev
    * It check if the installed version of acc is checked out to the latest commit and updates it if it's not.
    * */
    private fun checkUpdates(version: String) {
        launch {
            try {
                val lastCommit = GithubUtils.getLatestAccCommit(version)

                if (lastCommit != _preferences.lastCommit) {
                    _preferences.lastCommit = lastCommit

                    // Guard against showing a dialog on a finishing/destroyed activity after the
                    // network suspend -> android.view.WindowManager$BadTokenException.
                    if (isFinishing || isDestroyed) return@launch

                    MaterialDialog(this@MainActivity).show {
                        title(R.string.install_update_dialog)
                        message(text = getString(R.string.check_update_dialog_message, version))
                        positiveButton(android.R.string.yes) {
                            val dialog = MaterialDialog(this@MainActivity).show {
                                title(R.string.checking_updates)
                                progress(R.string.wait)
                                cancelOnTouchOutside(false)
                            }

                            launch {
                                try {
                                    val res = Acc.instance.upgrade(version)
                                    dialog.cancel()

                                    // The upgrade suspends; the activity may be gone by now.
                                    if (isFinishing || isDestroyed) return@launch

                                    when (res?.code) {
                                        6 ->
                                            Toast.makeText(
                                                this@MainActivity,
                                                R.string.no_update_available,
                                                Toast.LENGTH_LONG
                                            ).show()

                                        0 ->
                                            Toast.makeText(
                                                this@MainActivity,
                                                R.string.update_completed,
                                                Toast.LENGTH_LONG
                                            ).show()

                                        else -> {
                                            MaterialDialog(this@MainActivity) //Other installation errors can not be handled automatically -> show a dialog with the logs
                                                .show {
                                                    title(R.string.acc_installation_failed_title)
                                                    message(R.string.acc_installation_failed)
                                                    positiveButton(android.R.string.ok) {
                                                        initUi()
                                                    }
                                                    //TODO add logs
                                                    //shareLogsNeutralButton(File(filesDir, "logs/acc-install.log"), R.string.acc_installation_failed_log)

                                                    cancelOnTouchOutside(false)
                                                }
                                        }
                                    }
                                } catch (e: Exception) {
                                    LogExt().e(javaClass.simpleName, "ACC upgrade failed: ${e.message}")
                                    try { dialog.cancel() } catch (_: Exception) {}
                                }
                            }

                        }
                        negativeButton(android.R.string.no)
                    }
                }
            } catch (e: Exception) {
                LogExt().e(javaClass.simpleName, "checkUpdates failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        setTheme(R.style.AccaTheme_DayNight)
        super.onCreate(savedInstanceState)
        LogExt().d(javaClass.simpleName, "onCreate()")

        //--------------------------------------------------

        val spl = PreferenceManager.getDefaultSharedPreferences(this).getString("language", "def") ?: "def"

        val config = resources.configuration
        val locale = if (spl == "def") Locale.getDefault() else Locale(spl)

        Locale.setDefault(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) config.setLocale(locale) else config.locale = locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createConfigurationContext(config)

        resources.updateConfiguration(config, resources.displayMetrics)

        //--------------------------------------------------

        sendBroadcast(Intent(this, BatteryInfoWidget::class.java).setAction(WIDGET_ALL_UPDATE))

        //--------------------------------------------------

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setSupportActionBar(binding.mainToolbar)

        // Load preferences
        _preferences = Preferences(this)

        // Set theme
        setTheme()

        // Root check + ACC/DJS detection all happen OFF the main thread in detectAccAndInit()
        // (the first Shell.rootAccess() spawns the su daemon / root prompt and blocked the UI
        // to a blank screen when done here synchronously).
        detectAccAndInit()
    }

    /**
     * Function for setting the app's theme depending on saved preference.
     */
    private fun setTheme() {
        when (_preferences.appTheme) {
            "0" -> setDefaultNightMode(MODE_NIGHT_NO)
            "1" -> setDefaultNightMode(MODE_NIGHT_YES)
            "2" -> setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_appbar_menu, menu)
        return true
    }

    override fun onBackPressed() {
        if (binding.mainBottomNav.selectedItemId == R.id.botNav_home) {
            super.onBackPressed()
        } else {
            binding.mainBottomNav.selectedItemId = R.id.botNav_home
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            ACC_CONFIG_EDITOR_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.getBooleanExtra(Constants.ACC_HAS_CHANGES, false) == true) {
                        // Safe-cast the returned config; a missing/garbled extra must not crash.
                        val accConfig = data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig
                        if (accConfig != null) {
                            launch {
                                try {
                                    _sharedViewModel.updateAccConfig(accConfig)

                                    // Remove the current selected profile
                                    _sharedViewModel.clearCurrentSelectedProfile()
                                } catch (e: Exception) {
                                    LogExt().e(javaClass.simpleName, "updateAccConfig failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            ACC_PROFILE_CREATOR_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (data != null) {
                        val accConfig: AccConfig =
                            data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig ?: return
                        val profileNameRegex = """^[^\\/:*?"<>|]+${'$'}""".toRegex()
                        MaterialDialog(this)
                            .show {
                                title(R.string.profile_name)
                                message(R.string.dialog_profile_name_message)
                                input(waitForPositiveButton = false) { dialog, charSequence ->
                                    val inputField = dialog.getInputField()
                                    val isValid = profileNameRegex.matches(charSequence)

                                    inputField.error =
                                        if (isValid) null else getString(R.string.invalid_chars)
                                    dialog.setActionButtonEnabled(WhichButton.POSITIVE, isValid)
                                }
                                positiveButton(R.string.save) { dialog ->
                                    val profileName = dialog.getInputField().text.toString()
                                    // Add Profile to Database via ViewModel function
                                    val profile =
                                        AccaProfile(0, profileName, accConfig, ProfileEnables())
                                    _profilesViewModel.insertProfile(profile)
                                }
                                negativeButton(android.R.string.cancel)
                            }
                    }
                }
            }

            ACC_PROFILE_EDITOR_REQUEST -> // SQl DAO
            {
                if (resultCode == Activity.RESULT_OK)
                {
                    if (data?.getBooleanExtra(
                            Constants.ACC_HAS_CHANGES, false
                        ) == true && data.hasExtra(Constants.DATA_KEY))
                    {

                        val accConfig: AccConfig =
                            data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig ?: return
                        val editorData = data.getBundleExtra(Constants.DATA_KEY) ?: return
                        val profileId = editorData.getInt(Constants.PROFILE_ID_KEY)

                        launch {
                            _profilesViewModel.getProfileById(profileId)?.let { selectedProfile ->
                                // Update the profile with new accConfig
                                _profilesViewModel.updateProfile(selectedProfile.copy(accConfig = accConfig))
                            }
                        }
                    }
                }
            }

            ACC_ADD_PROFILE_SCHEDULER_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.hasExtra(Constants.DATA_KEY) == true) {
                        data.getBundleExtra(Constants.DATA_KEY)?.let { dataBundle ->
                            val scheduleName =
                                dataBundle.getString(Constants.SCHEDULE_NAME_KEY) ?: return
                            val time = dataBundle.getString(Constants.SCHEDULE_TIME_KEY) ?: return
                            val executeOnce =
                                dataBundle.getBoolean(Constants.SCHEDULE_EXEC_ONCE_KEY)
                            val executeOnBoot =
                                dataBundle.getBoolean(Constants.SCHEDULE_EXEC_ONBOOT_KEY)

                            _schedulesViewModel
                                .addSchedule(
                                    scheduleName,
                                    time,
                                    executeOnce,
                                    executeOnBoot,
                                    data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig
                                        ?: return
                                )
                        }
                    }
                }
            }
            ACC_EDIT_PROFILE_SCHEDULER_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.hasExtra(Constants.DATA_KEY) == true) {
                        data.getBundleExtra(Constants.DATA_KEY)?.let { dataBundle ->
                            val id = dataBundle.getInt(Constants.SCHEDULE_ID_KEY)
                            val scheduleName =
                                dataBundle.getString(Constants.SCHEDULE_NAME_KEY) ?: return
                            val time = dataBundle.getString(Constants.SCHEDULE_TIME_KEY) ?: return
                            val executeOnce =
                                dataBundle.getBoolean(Constants.SCHEDULE_EXEC_ONCE_KEY)
                            val executeOnBoot =
                                dataBundle.getBoolean(Constants.SCHEDULE_EXEC_ONBOOT_KEY)
                            val enabled = dataBundle.getBoolean(Constants.SCHEDULE_ENABLED_KEY)

                            _schedulesViewModel
                                .editSchedule(
                                    id,
                                    scheduleName,
                                    enabled,
                                    time,
                                    executeOnce,
                                    executeOnBoot,
                                    data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig
                                        ?: return
                                )
                        }
                    }
                }
            }
            ACC_IMPORT_PROFILE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    // todo: read seralized profiles here to import via ViewModel
                    // Safe-cast the serialized list: a missing/garbled extra returns
                    // null instead of throwing ClassCastException/NPE.
                    @Suppress("UNCHECKED_CAST")
                    val imports =
                        data?.getSerializableExtra(Constants.DATA_KEY) as? List<ProfileEntry>
                    if (!imports.isNullOrEmpty()) {
                        for (entry: ProfileEntry in imports) {
                            _profilesViewModel.insertProfile(
                                AccaProfile(
                                    0,
                                    entry.getName(),
                                    entry.getConfig(),
                                    ProfileEnables()
                                )
                            )
                        }
                        Toast.makeText(
                            this,
                            getString(R.string.import_profile_success, imports.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun accScheduleFabOnClick(view: View) {
        // Scheduler runs on DJS. If it's not enabled, say so and route to Settings instead of
        // silently doing nothing. djsEnabled is cached, so this never blocks the UI thread.
        if (!_preferences.djsEnabled) {
            MaterialDialog(this).show {
                title(text = "DJS required")
                message(text = "The Scheduler needs DJS (Daily Job Scheduler). Enable it in Settings → DJS, then try again.")
                positiveButton(text = "Open Settings") { SettingsActivity.launch(this@MainActivity) }
                negativeButton(android.R.string.cancel)
            }
            return
        }
        MaterialDialog(this).show {
            title(R.string.create_schedule)
            addScheduleDialog(_profilesViewModel.getLiveData()) { profileId, scheduleName, time, executeOnce, executeOnBoot ->
                if (profileId == -1L) {
                    launch {
                        try {
                            // readDefaultConfig() blocks on root -> resolve it before building the
                            // intent and guard the activity before navigating.
                            val defaultConfig = withContext(Dispatchers.IO) { Acc.instance.readDefaultConfig() }
                            if (isFinishing || isDestroyed) return@launch
                            Intent(
                                this@MainActivity,
                                AccConfigEditorActivity::class.java
                            ).also { intent ->
                                val dataBundle = Bundle()
                                dataBundle.putString(Constants.SCHEDULE_NAME_KEY, scheduleName)
                                dataBundle.putString(Constants.SCHEDULE_TIME_KEY, time)
                                dataBundle.putBoolean(Constants.SCHEDULE_EXEC_ONCE_KEY, executeOnce)
                                dataBundle.putBoolean(
                                    Constants.SCHEDULE_EXEC_ONBOOT_KEY,
                                    executeOnBoot
                                )

                                intent.putExtra(Constants.DATA_KEY, dataBundle)
                                intent.putExtra(
                                    Constants.ACC_CONFIG_KEY,
                                    defaultConfig
                                )
                                intent.putExtra(
                                    Constants.TITLE_KEY,
                                    getString(R.string.schedule_creator)
                                )
                                startActivityForResult(intent, ACC_ADD_PROFILE_SCHEDULER_REQUEST)
                            }
                        } catch (e: Exception) {
                            LogExt().e(javaClass.simpleName, "Schedule creator launch failed: ${e.message}")
                        }
                    }
                } else {
                    launch {
                        try {
                            _profilesViewModel.getProfileById(profileId.toInt())
                                ?.let { configProfile ->
                                    _schedulesViewModel
                                        .addSchedule(
                                            scheduleName,
                                            time,
                                            executeOnce,
                                            executeOnBoot,
                                            configProfile.accConfig
                                        )
                                }
                        } catch (e: Exception) {
                            LogExt().e(javaClass.simpleName, "addSchedule failed: ${e.message}")
                        }
                    }
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    fun editSchedule(schedule: Schedule) {
        MaterialDialog(this).show {
            title(R.string.edit_schedule)
            editScheduleDialog(
                schedule,
                _profilesViewModel.getLiveData()
            ) { profileId, scheduleName, time, executeOnce, executeOnBoot ->
                when (profileId) {
                    -1L -> //keep current config
                        _schedulesViewModel
                            .editSchedule(
                                schedule.profile.uid,
                                scheduleName,
                                schedule.isEnabled,
                                time,
                                executeOnce,
                                executeOnBoot,
                                schedule.profile.accConfig
                            )
                    -2L -> //edit current config
                        Intent(
                            this@MainActivity,
                            AccConfigEditorActivity::class.java
                        ).also { intent ->
                            val dataBundle = Bundle()
                            dataBundle.putInt(Constants.SCHEDULE_ID_KEY, schedule.profile.uid)
                            dataBundle.putString(Constants.SCHEDULE_NAME_KEY, scheduleName)
                            dataBundle.putString(Constants.SCHEDULE_TIME_KEY, time)
                            dataBundle.putBoolean(Constants.SCHEDULE_EXEC_ONCE_KEY, executeOnce)
                            dataBundle.putBoolean(
                                Constants.SCHEDULE_EXEC_ONBOOT_KEY,
                                executeOnBoot
                            )
                            dataBundle.putBoolean(
                                Constants.SCHEDULE_ENABLED_KEY,
                                schedule.isEnabled
                            )

                            intent.putExtra(Constants.DATA_KEY, dataBundle)
                            intent.putExtra(
                                Constants.TITLE_KEY,
                                getString(R.string.schedule_creator)
                            )
                            intent.putExtra(
                                Constants.ACC_CONFIG_KEY,
                                schedule.profile.accConfig
                            )

                            startActivityForResult(intent, ACC_EDIT_PROFILE_SCHEDULER_REQUEST)
                        }
                    -3L -> //new custom config
                        launch {
                            Intent(
                                this@MainActivity,
                                AccConfigEditorActivity::class.java
                            ).also { intent ->
                                val dataBundle = Bundle()
                                dataBundle.putInt(
                                    Constants.SCHEDULE_ID_KEY,
                                    schedule.profile.uid
                                )
                                dataBundle.putString(Constants.SCHEDULE_NAME_KEY, scheduleName)
                                dataBundle.putString(Constants.SCHEDULE_TIME_KEY, time)
                                dataBundle.putBoolean(
                                    Constants.SCHEDULE_EXEC_ONCE_KEY,
                                    executeOnce
                                )
                                dataBundle.putBoolean(
                                    Constants.SCHEDULE_EXEC_ONBOOT_KEY,
                                    executeOnBoot
                                )
                                dataBundle.putBoolean(
                                    Constants.SCHEDULE_ENABLED_KEY,
                                    schedule.isEnabled
                                )

                                intent.putExtra(Constants.DATA_KEY, dataBundle)
                                intent.putExtra(
                                    Constants.TITLE_KEY,
                                    getString(R.string.schedule_creator)
                                )
                                intent.putExtra(
                                    Constants.ACC_CONFIG_KEY,
                                    withContext(Dispatchers.IO) { Acc.instance.readDefaultConfig() }
                                )
                                startActivityForResult(
                                    intent,
                                    ACC_EDIT_PROFILE_SCHEDULER_REQUEST
                                )
                            }
                        }
                    else -> launch {
                        _profilesViewModel.getProfileById(profileId.toInt())
                            ?.let { configProfile ->
                                _schedulesViewModel
                                    .editSchedule(
                                        schedule.profile.uid,
                                        scheduleName,
                                        schedule.isEnabled,
                                        time,
                                        executeOnce,
                                        executeOnBoot,
                                        configProfile.accConfig
                                    )
                            }
                    }
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

}
package mattecarra.accapp.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.activities.AccConfigEditorActivity
import mattecarra.accapp.databinding.ProfilesItemBinding
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ProfileUtils
import mattecarra.accapp.utils.ScopedFragment
import com.afollestad.materialdialogs.MaterialDialog
import mattecarra.accapp.viewmodel.ProfilesViewModel
import mattecarra.accapp.viewmodel.SharedViewModel

class DashboardConfigFragment() : ScopedFragment(), SharedPreferences.OnSharedPreferenceChangeListener  {
    private lateinit var mContext: Context
    private lateinit var mViewModel: ProfilesViewModel
    private lateinit var mSharedViewModel: SharedViewModel
    private lateinit var mPrefs: SharedPreferences

    private var mActiveProfile: Boolean = false

    private var _binding: ProfilesItemBinding? = null
    private val binding get() = _binding!!

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 7 && resultCode == Activity.RESULT_OK && data?.getBooleanExtra(Constants.ACC_HAS_CHANGES, false) == true)
        {
            LogExt().d(javaClass.simpleName,"onActivityResult(): ACC_HAS_CHANGES=true")

            // Safe-cast the returned config; a missing/garbled extra must not crash.
            val accConfig = data.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig ?: return

            launch {
                mSharedViewModel.updateAccConfig(accConfig)

                // Editing from the dashboard used to detach the selected profile unconditionally:
                // any tweak silently turned "Balanced" into a custom setup, with no prompt and no
                // way to fold the change back into the profile. Reported from the field, together
                // with the other half of the same problem -- once PROFILE_KEY is -1 the dashboard
                // has no profile to follow, so later edits in the profile manager appear to do
                // nothing.
                //
                // The live config genuinely no longer matches the saved profile here, so something
                // has to give; which one is the user's call. Ask, and let them keep the profile
                // updated instead of losing it.
                val currentId = ProfileUtils.getCurrentProfile(mPrefs)
                val profile = if (currentId >= 0) mViewModel.getProfileById(currentId) else null

                // updateAccConfig suspends; the view may be gone now. Bail if detached.
                if (_binding == null || !isAdded) return@launch

                if (profile == null) {
                    // Nothing was selected, so there is no profile to keep or update.
                    mSharedViewModel.clearCurrentSelectedProfile()
                    updateInfo(getString(R.string.profile_not_selected), accConfig)
                } else {
                    MaterialDialog(mContext).show {
                        title(R.string.profile_edited_keep_title)
                        message(text = getString(R.string.profile_edited_keep_message, profile.profileName))
                        cancelOnTouchOutside(false)
                        positiveButton(R.string.profile_edited_update) {
                            profile.accConfig = accConfig
                            mViewModel.updateProfile(profile)
                            if (_binding != null && isAdded) updateInfo(profile.profileName, accConfig)
                        }
                        negativeButton(R.string.profile_edited_detach) {
                            mSharedViewModel.clearCurrentSelectedProfile()
                            if (_binding != null && isAdded)
                                updateInfo(getString(R.string.profile_not_selected), accConfig)
                        }
                    }
                }
            }
        }
    }

    companion object
    {
        fun newInstance() = DashboardConfigFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        _binding = ProfilesItemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName,"onViewCreated()")
        super.onViewCreated(view, savedInstanceState)

        binding.itemProfileLoadImage.visibility = View.VISIBLE;
        binding.itemProfileInfo.visibility = View.GONE;
        binding.editConfigButton.visibility = View.VISIBLE;

        mContext = requireContext()
        mViewModel = ViewModelProvider(this).get(ProfilesViewModel::class.java)
        // Use the activity scope so this shares the same SharedViewModel instance
        // that DashboardFragment uses (it also scopes to the activity); a
        // fragment-scoped provider would create a divergent second instance.
        mSharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        mPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        mPrefs.registerOnSharedPreferenceChangeListener(this)

        view.setOnClickListener(View.OnClickListener {
            startAccConfigEditorActivity()
        })

        binding.editConfigButton.setOnClickListener {
            startAccConfigEditorActivity()
        }

        // Single source of truth for what this panel shows.
        //
        // Before this, the panel refreshed only on resume and on PROFILE_KEY changing. Applying a
        // profile sets PROFILE_KEY BEFORE its ACC write completes (the "A1" race documented in
        // ProfilesFragment), so checkProfile() read the config as it was a moment earlier and
        // nothing ever re-read it. Users applied a profile of 100/40 and the dashboard kept
        // showing 75/70 - not stale rendering, but a genuinely stale read.
        //
        // SharedViewModel.config is posted after every apply, by every writer (profile apply,
        // config editor, schedules), so observing it means the panel cannot miss a change and
        // cannot show a value ACC does not hold. checkProfile() stays for the profile NAME, which
        // is a database lookup rather than a config read.
        mSharedViewModel.observeConfig(viewLifecycleOwner) { (config, _) ->
            if (config == null || _binding == null || !isAdded) return@observeConfig
            val profileId = ProfileUtils.getCurrentProfile(mPrefs)
            launch {
                val name = if (profileId != -1) mViewModel.getProfileById(profileId)?.profileName
                                                ?: getString(R.string.profile_not_selected)
                           else getString(R.string.profile_not_selected)
                if (_binding == null || !isAdded) return@launch
                updateInfo(name, config)
            }
        }

        checkProfile()
    }

    private fun startAccConfigEditorActivity()
    {
        // When a profile is active the gear must edit THAT profile, not the global config behind
        // it. Editing the global config while a profile is selected silently diverges the two:
        // the dashboard shows one thing, the profile stores another, and re-applying the profile
        // throws the edit away. Passing the profile makes the editor operate on the object the
        // user believes they are editing.
        val profileId = ProfileUtils.getCurrentProfile(mPrefs)
        val intent = Intent(context, AccConfigEditorActivity::class.java)
        if (profileId != -1) {
            launch {
                val profile = mViewModel.getProfileById(profileId)
                if (profile != null) {
                    intent.putExtra(Constants.PROFILE_ID_KEY, profile.uid)
                        .putExtra(Constants.PROFILE_CONFIG_KEY, profile)
                        .putExtra(Constants.TITLE_KEY, profile.profileName)
                }
                startActivityForResult(intent, 7)
            }
            return
        }
        startActivityForResult(intent, 7)
    }

    fun checkProfile()
    {
        launch {

            val profileId = ProfileUtils.getCurrentProfile(mPrefs)
            // readConfig() is a blocking root call; run it off the main thread and
            // never let a failure crash the dashboard.
            val currentConfig = try {
                withContext(Dispatchers.IO) { Acc.instance.readConfig() }
            } catch (e: Exception) {
                LogExt().e(javaClass.simpleName, "checkProfile() readConfig failed: ${e.message}")
                return@launch
            }
            val selProfile = mViewModel.getProfileById(profileId)

            // The view may have been torn down while we awaited; bail if detached.
            if (_binding == null || !isAdded) return@launch

            // Trust the stored id for the NAME (not exact config-equality, which ACC's
            // write-normalization breaks -- same A2 flaw as the list). Still show the live
            // config values via updateInfo. Selection is cleared by setting PROFILE_KEY=-1.
            val name = if (profileId != -1 && selProfile != null) selProfile.profileName
                       else getString(R.string.profile_not_selected)

            updateInfo(name, currentConfig)
        }
    }

    fun updateInfo(nameTitle: String, accConfig: AccConfig)
    {
        LogExt().d(javaClass.simpleName, "updateInfo(): name=$nameTitle , accConfig=$accConfig")

        binding.itemProfileTitleTextView.text = nameTitle
        // Capacity was the only row rendered unconditionally: every sibling below has an
        // isGone/isVisible guard, so a user who turned capacity control off still saw a live-
        // looking "Shutdown 5% - Resume 70% - Stop 75%" line reporting a limit ACC was not
        // enforcing. Show the disabled text rather than hiding the row outright, so the state is
        // explicit instead of the setting simply vanishing.
        // isEnabled only GREYED the row, so a disabled capacity limit still occupied the card and
        // still read like a live setting. Every sibling row below uses isGone/isVisible; match them.
        // toString() already returns the "disabled" wording, so the text stays correct either way.
        binding.itemProfileCapacityTv.text = accConfig.configCapacity.toString(mContext)
        binding.itemProfileCapacityTv.isEnabled = accConfig.configCapacity.isEnabled
        binding.itemProfileCapacityLl.isVisible = accConfig.configCapacity.isEnabled

        binding.itemProfileSwitchLl.isGone = accConfig.configChargeSwitch.isNullOrEmpty()
        binding.itemProfileSwitchDataTv.text = accConfig.configChargeSwitch ?: mContext.getString(R.string.automatic)
        binding.itemProfileAutomaticSwitchingTv.isVisible = accConfig.configIsAutomaticSwitchingEnabled

        //-----------------------------------------------

        binding.itemProfileChargingVoltageLl.isVisible = (accConfig.configVoltage.controlFile != null || accConfig.configVoltage.max != null || accConfig.configCurrMax != null)

        binding.itemProfileChargingVoltageTv.text = accConfig.configVoltage.toString(mContext)
        binding.itemProfileCurrentMaxTv.text = mContext.getString(R.string.current_max) +" "+ accConfig.configCurrMax.toString()

        val volt = (accConfig.configVoltage.controlFile != null || accConfig.configVoltage.max != null)
        val currmax = accConfig.configCurrMax != null

        // Unconditional: the old XOR guard skipped the visibility update when BOTH limits were
        // set, so the rows kept whatever the previous bind showed (usually voltage only) and a
        // voltage+current config displayed just one of them. The two limits are independent
        // (CC-phase current cap vs CV-phase voltage cap) and must both be visible.
        binding.itemProfileChargingVoltageTv.isVisible = volt
        binding.itemProfileCurrentMaxTv.isVisible = currmax

        //-----------------------------------------------

        binding.itemProfileTemperatureTv.text = accConfig.configTemperature.toString(mContext)

        binding.itemProfileCooldownLl.isVisible = accConfig.configCoolDown != null
        binding.itemProfileCooldownTv.text = if (accConfig.configCoolDown == null) "-"
        else accConfig.configCoolDown?.toString(mContext)

        binding.itemProfileOnBootLl.isVisible = accConfig.configOnBoot != null
        binding.itemProfileOnBootTv.text = if (accConfig.configOnBoot == null) "-"
        else accConfig.configOnBoot

        binding.itemProfileOnPlugLl.isVisible = accConfig.configOnPlug != null
        binding.itemProfileOnPlugTv.text = if (accConfig.configOnPlug == null) "-"
        else accConfig.getOnPlug(mContext)

        binding.itemProfilePrioritizeBatteryIdleTv.isVisible = accConfig.prioritizeBatteryIdleMode
        binding.itemProfileResetBsOnPauseTv.isVisible = accConfig.configResetBsOnPause
        binding.itemProfileResettUnpluggedTv.isVisible = accConfig.configResetUnplugged

        binding.itemProfileOptionsIb.visibility = View.GONE
        binding.itemProfileSelectedIndicatorView.isVisible = mActiveProfile

        binding.itemProfileLoadImage.visibility = View.GONE;
        binding.itemProfileInfo.visibility = View.VISIBLE;
    }

    override fun onDestroyView()
    {
        super.onDestroyView()
        // Unregister the pref listener and null the binding so post-teardown
        // callbacks/coroutines see a destroyed view and bail.
        if (::mPrefs.isInitialized) mPrefs.unregisterOnSharedPreferenceChangeListener(this)
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    {
        // key can be null when all prefs are cleared (Android 11+); compare safely.
        if (key == Constants.PROFILE_KEY) checkProfile()
    }
}
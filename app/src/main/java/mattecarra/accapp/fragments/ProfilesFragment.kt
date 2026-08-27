package mattecarra.accapp.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp._interface.OnProfileClickListener
import mattecarra.accapp.activities.AccConfigEditorActivity
import mattecarra.accapp.adapters.ProfileListAdapter
import mattecarra.accapp.databinding.ProfilesFragmentBinding
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.AccaProfile
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ProfileUtils
import mattecarra.accapp.utils.ScopedFragment
import mattecarra.accapp.viewmodel.ProfilesViewModel
import mattecarra.accapp.viewmodel.SharedViewModel
import xml.BatteryInfoWidget
import xml.WIDGET_ALL_UPDATE

// Fragments from: https://codeburst.io/android-swipe-menu-with-recyclerview-8f28a235ff28

class ProfilesFragment : ScopedFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    OnProfileClickListener
{
    companion object
    {
        fun newInstance() = ProfilesFragment()
    }

    private lateinit var mProfilesViewModel: ProfilesViewModel
    private lateinit var mSharedViewModel: SharedViewModel
    private lateinit var mProfilesAdapter: ProfileListAdapter
    private lateinit var mContext: Context
    // Held so the OnSharedPreferenceChangeListener can be unregistered in onDestroyView
    // (registering without unregistering leaks the fragment).
    private var mPrefs: SharedPreferences? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 7 && resultCode == Activity.RESULT_OK && data?.getBooleanExtra(Constants.ACC_HAS_CHANGES, false) == true)
        {
            // Safe-cast the returned profile; a missing/garbled extra must not crash.
            val newProfile = data.getSerializableExtra(Constants.PROFILE_CONFIG_KEY) as? AccaProfile ?: return

            launch {
                mProfilesViewModel.updateProfile(newProfile)
                Toast.makeText(mContext, mContext.getString(R.string.profile_tile_label, newProfile.profileName) + '\n' + mContext.getString(R.string.update_completed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        return ProfilesFragmentBinding.inflate(inflater, container, false).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onViewCreated()")

        val binding = ProfilesFragmentBinding.bind(view)
        val profilesRecycler = binding.profileRecyclerView

        mContext = requireContext()

        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        mPrefs = prefs

        // Activity scope (not fragment) so this is the SAME SharedViewModel instance the
        // Dashboard and MainActivity use -- a config applied from here then reaches them
        // instead of updating a divergent fragment-private copy (A4).
        mSharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)
        mProfilesAdapter = ProfileListAdapter(mContext, ProfileUtils.getCurrentProfile(prefs))
        mProfilesAdapter.setOnClickListener(this)

        profilesRecycler.adapter = mProfilesAdapter
        profilesRecycler.layoutManager = LinearLayoutManager(mContext)

        mProfilesViewModel = ViewModelProvider(this).get(ProfilesViewModel::class.java)

        // Observe data
        mProfilesViewModel.getLiveData().observe(viewLifecycleOwner, Observer { profiles ->
            if (profiles.isEmpty()) {
                binding.profilesEmptyTextview.visibility = View.VISIBLE
                profilesRecycler.visibility = View.GONE
            } else {
                binding.profilesEmptyTextview.visibility = View.GONE
                profilesRecycler.visibility = View.VISIBLE
            }
            mProfilesAdapter.setProfiles(profiles)
        })

        prefs.registerOnSharedPreferenceChangeListener(this)

        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

            private var swipeBack: Boolean = true
            private val background = ColorDrawable()
            private val backgroundColour = ContextCompat.getColor(mContext, R.color.colorSuccessful)
            private val applyIcon = ContextCompat.getDrawable(mContext, R.drawable.ic_outline_check_circle_24px
            )
            private val intrinsicWidth = applyIcon?.intrinsicWidth ?: 0
            private val intrinsicHeight = applyIcon?.intrinsicHeight ?: 0

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int)
            {}

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean
            {
                return false // No up and down movement
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean)
            {
                if (actionState == ACTION_STATE_SWIPE)
                {
                    setTouchListener(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                // Draw background
                val itemView = viewHolder.itemView
                val itemHeight = itemView.bottom - itemView.top
                background.color = backgroundColour

                if (dX < 0) {

                    background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    background.draw(c)

                    // Determine icon dimensions
                    val iconTop = itemView.top + (itemHeight - intrinsicHeight) / 2
                    val iconMargin = (itemHeight - intrinsicHeight) / 2
                    val iconLeft = itemView.right - iconMargin - intrinsicWidth
                    val iconRight = itemView.right - iconMargin
                    val iconBottom = iconTop + intrinsicWidth

                    // Draw the apply icon (skip if the drawable failed to load)
                    applyIcon?.let { icon ->
                        val wrapped = DrawableCompat.wrap(icon)
                        DrawableCompat.setTint(wrapped, Color.WHITE)
                        wrapped.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                        wrapped.draw(c)
                    }
                }

                if (dX > 0) {

                    background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    background.draw(c)

                    // Determine icon dimensions
                    val iconTop = itemView.top + (itemHeight - intrinsicHeight) / 2
                    val iconMargin = (itemHeight - intrinsicHeight) / 2
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = itemView.left + iconMargin + intrinsicWidth
                    val iconBottom = iconTop + intrinsicWidth

                    // Draw the apply icon (skip if the drawable failed to load)
                    applyIcon?.let { icon ->
                        val wrapped = DrawableCompat.wrap(icon)
                        DrawableCompat.setTint(wrapped, Color.WHITE)
                        wrapped.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                        wrapped.draw(c)
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            @SuppressLint("ClickableViewAccessibility")
            private fun setTouchListener(
                canvas: Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float,
                actionState: Int, isCurrentlyActive: Boolean
            ) {

                recyclerView.setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                        when (event?.action) {
                            MotionEvent.ACTION_CANCEL -> swipeBack = true
                            MotionEvent.ACTION_UP -> swipeBack = true
                        }

                        if (swipeBack) {

                            // getProfileAt is bounds-checked (null on NO_POSITION);
                            // only fire the click when a real profile resolves.
                            if (dX > 300) { // If slid towards right > 300px?, adjust for sensitivity
                                mProfilesAdapter.getProfileAt(viewHolder.adapterPosition)?.let { onProfileClick(it) }
                            }
                            if (dX < -300) { // Show right side
                                mProfilesAdapter.getProfileAt(viewHolder.adapterPosition)?.let { onProfileClick(it) }
                            }
                        }

                        return false
                    }
                })
            }

            override fun convertToAbsoluteDirection(flags: Int, layoutDirection: Int): Int
            {
                if (swipeBack) { swipeBack = false ; return 0 }
                return super.convertToAbsoluteDirection(flags, layoutDirection)
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(profilesRecycler)
    }

    override fun onDestroyView()
    {
        // Unregister the prefs listener tied to this view to avoid leaking the fragment.
        mPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        mPrefs = null
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    {
        // key can be null when all prefs are cleared (Android 11+); compare safely.
        if (sharedPreferences != null && key == Constants.PROFILE_KEY)
        {
            // Trust the stored profile id -- just highlight it. The previous version
            // re-read the live ACC config here and CLEARED the selection when it did not
            // exactly equal the stored profile. Two bugs lived in that:
            //   (A1 race) onProfileClick writes PROFILE_KEY *before* its 11-command apply
            //     finishes, so this listener fired mid-apply, read a half-applied config,
            //     saw a mismatch, and cleared the just-made selection every time.
            //   (A2 equality) ACC normalizes on write (resume_temp clamp, control-file
            //     dropped, unit coercion), so the parsed config can NEVER data-class-equal
            //     the stored profile for many profiles -> permanent false clears.
            // Selection is cleared explicitly elsewhere (editing the global config sets
            // PROFILE_KEY = -1, which lands here and un-highlights via setActiveProfile(-1)).
            mProfilesAdapter.setActiveProfile(ProfileUtils.getCurrentProfile(sharedPreferences))
        }
    }

    /**
     * Override function for handling ProfileOnClicks
     * Applies the selected profile as CURRENT!
     */
    override fun onProfileClick(profile: AccaProfile)
    {
        LogExt().d(javaClass.simpleName, "onProfileClick(${profile.uid}): "+ profile.profileName)

        // APPLYING IS RECOVERABLE. One tap on a card used to overwrite the live ACC config with no
        // confirmation and no way back: a field report described brushing a profile while reading
        // the list and having to re-import a settings backup to undo it.
        //
        // Moving apply behind a menu was the other option, and it is worse: applying is the reason
        // this screen exists, and it would cost every user two taps to protect against a rare
        // accident. So keep the single tap and make the accident cost nothing -- snapshot the live
        // config first, then offer UNDO for as long as the snackbar is up.
        launch {
            val previous = try { Acc.instance.readConfig() } catch (e: Exception) {
                LogExt().d(javaClass.simpleName, "could not snapshot the live config for undo: $e")
                null
            }

            // configForApply(), not accConfig: a profile with capacity control toggled off must push
            // pause=100 (no limit) rather than its stale numbers. See AccaProfile.configForApply.
            val applied = mSharedViewModel.updateAccConfig(profile.configForApply(), profile.pEnables)
            // Persist "this profile is current" ONLY when ACC accepted it. This used to be saved before
            // the apply was even attempted, so a failed or partial apply still left the app and the
            // widget naming a profile the daemon was not running. The quick-settings tile has always
            // had this contract; the UI paths now match it.
            if (applied) mSharedViewModel.setCurrentSelectedProfile(profile.uid)
            mContext.sendBroadcast(Intent(mContext, BatteryInfoWidget::class.java)
                .setAction(WIDGET_ALL_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            // TEN SECONDS, not LENGTH_LONG. Applying a profile issues a dozen sequential root
            // commands and takes several seconds, and the snackbar can only be shown once that
            // finishes -- so LENGTH_LONG's 3.5s started late AND ran out fast. Measured on a Pixel
            // 6a: the button was frequently gone before it could be pressed, which makes an undo
            // that technically exists useless in practice.
            val bar = Snackbar.make(
                requireView(),
                getString(R.string.selecting_profile_toast, profile.profileName),
                10_000)

            // No snapshot means no honest undo. Say nothing rather than offer a button that would
            // quietly do nothing -- an UNDO that does not undo is worse than no UNDO at all.
            if (previous != null) {
                // this@ProfilesFragment.launch, NOT a bare launch.
                //
                // Inside launch { } the receiver is THAT COROUTINE'S scope. A bare `launch` in this
                // click listener therefore attached the undo to the coroutine that showed the
                // snackbar -- which has already completed by the time anyone can press the button,
                // so the child was never started. The button dismissed the bar and did absolutely
                // nothing: verified on a Pixel 6a, click registered, zero acca commands emitted.
                // It compiles clean, which is why this only showed up on hardware.
                bar.setAction(R.string.undo) {
                    this@ProfilesFragment.launch {
                        mSharedViewModel.updateAccConfig(previous)
                        mContext.sendBroadcast(Intent(mContext, BatteryInfoWidget::class.java)
                            .setAction(WIDGET_ALL_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Toast.makeText(mContext,
                            getString(R.string.profile_apply_undone), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            bar.show()
        }
    }

    override fun onProfileLongClick(profile: AccaProfile)
    {
    }

    override fun editProfile(profile: AccaProfile)
    {
        // Edit the configuration of the selected profile.
        startActivityForResult(Intent(mContext, AccConfigEditorActivity::class.java)
            .putExtra(Constants.PROFILE_ID_KEY, profile.uid)
            .putExtra(Constants.PROFILE_CONFIG_KEY, profile)
            .putExtra(Constants.TITLE_KEY, profile.profileName), 7)
    }

    override fun renameProfile(profile: AccaProfile)
    {
        // Rename the selected profile (2nd option).
        MaterialDialog(mContext).show {
                title(R.string.profile_name)
                message(R.string.dialog_profile_name_message)
                input(prefill = profile.profileName) { _, charSequence ->
                    // Set profile name
                    profile.profileName = charSequence.toString()
                    // Update the profile in the DB
                    mProfilesViewModel.updateProfile(profile)
                }
                positiveButton(R.string.save)
                negativeButton(android.R.string.cancel)
            }
    }

    override fun deleteProfile(profile: AccaProfile)
    {
        // Delete the selected profile (3rd option).
        mProfilesViewModel.deleteProfile(profile)
    }
}

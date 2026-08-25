package mattecarra.accapp.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import mattecarra.accapp.R
import mattecarra.accapp._interface.OnProfileClickListener
import mattecarra.accapp.databinding.ProfilesItemBinding
import mattecarra.accapp.models.AccaProfile

class ProfileListAdapter internal constructor(context: Context, activeProfileId: Int) :
    RecyclerView.Adapter<ProfileListAdapter.ProfileViewHolder>()
{
    private val mInflater: LayoutInflater = LayoutInflater.from(context)
    private lateinit var mListener: OnProfileClickListener
    private val mContext = context

    private var mProfilesList = emptyList<AccaProfile>()
    private var mActiveProfileId: Int = activeProfileId

    inner class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener, View.OnLongClickListener
    {
        val content = ProfilesItemBinding.bind(itemView)

        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
        }

        override fun onClick(v: View?)
        {
            // adapterPosition is NO_POSITION (-1) mid-animation/after removal;
            // getOrNull avoids an IndexOutOfBounds crash on a stale click.
            mProfilesList.getOrNull(adapterPosition)?.let { mListener.onProfileClick(it) }
        }

        override fun onLongClick(v: View?): Boolean
        {
            //  mListener.onProfileLongClick(mProfilesList[adapterPosition])
            mProfilesList.getOrNull(adapterPosition)?.let { mListener.editProfile(it) }
            return true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder
    {
        val itemView = mInflater.inflate(R.layout.profiles_item, parent, false)
        return ProfileViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int)
    {
        val profile = mProfilesList[position]

        holder.content.itemProfileTitleTextView.text = profile.profileName

        // Two flags described the same thing and could disagree: pEnables.eCapacity is the user's
        // intent, ConfigCapacity.isEnabled is derived from the numbers ACC actually enforces. A
        // profile saved before the toggle also wrote pause=DISABLED still carries eCapacity=false
        // with a live pause value, so honour BOTH -- the row is only shown when the user wants it
        // AND the numbers would really be enforced. That also makes an old profile render the same
        // way here as on the dashboard, which reads the derived flag.
        holder.content.itemProfileCapacityLl.isVisible =
            profile.pEnables.eCapacity && profile.accConfig.configCapacity.isEnabled
        holder.content.itemProfileCapacityTv.text = profile.accConfig.configCapacity.toString(mContext)

        // TODO You must make a switch as a separate item for manual mode or use the parameters from the global configuration in the settings. Here only the display of the selected option.
        holder.content.itemProfileSwitchLl.isVisible = profile.pEnables.eChargingSwitch
        holder.content.itemProfileSwitchDataTv.text = profile.accConfig.configChargeSwitch ?: mContext.getString(R.string.automatic)
        // Applying a profile with an explicit switch always writes the " --" manual lock, so
        // "Automatically cycle through switches" would be a promise the save path breaks.
        // Only a profile with NO pinned switch actually leaves ACC free to cycle.
        holder.content.itemProfileAutomaticSwitchingTv.visibility = View.GONE

        //----------------------------------------------------

        holder.content.itemProfileChargingVoltageLl.isVisible = profile.pEnables.eVoltage || profile.pEnables.eCurrMax
        holder.content.itemProfileChargingVoltageTv.text = profile.accConfig.configVoltage.toString(mContext)
        holder.content.itemProfileCurrentMaxTv.text = mContext.getString(R.string.current_max) + " " + profile.accConfig.configCurrMax.toString()

        val volt = (profile.accConfig.configVoltage.controlFile != null || profile.accConfig.configVoltage.max != null)
        val currmax = profile.accConfig.configCurrMax != null

        // Unconditional, for the reason already documented in DashboardConfigFragment: the XOR
        // guard skipped the visibility update whenever BOTH limits were set, so a recycled row kept
        // the previous item's visibility and a voltage+current profile showed only one of them.
        // The two limits are independent (CC-phase current cap vs CV-phase voltage cap).
        holder.content.itemProfileChargingVoltageTv.isVisible = volt
        holder.content.itemProfileCurrentMaxTv.isVisible = currmax

        //----------------------------------------------------

        holder.content.itemProfileTemperatureLl.isVisible = profile.pEnables.eTemperature
        holder.content.itemProfileTemperatureTv.text = profile.accConfig.configTemperature.toString(mContext)

        // Off (cooldown_capacity=101) reads as "not configured" to the user, so the row goes
        // away entirely rather than printing a disabled setting.
        val coolDown = profile.accConfig.configCoolDown?.takeIf { !it.isCapacityTriggerOff }
        holder.content.itemProfileCooldownLl.isVisible = profile.pEnables.eCoolDown && coolDown != null
        holder.content.itemProfileCooldownTv.text = coolDown?.toString(mContext) ?: "-"

        holder.content.itemProfileOnBootLl.isVisible = profile.pEnables.eRunOnBoot
        holder.content.itemProfileOnBootTv.text =
            if (profile.accConfig.configOnBoot == null) "-"
            else profile.accConfig.configOnBoot

        holder.content.itemProfileOnPlugLl.isVisible = profile.pEnables.eRunOnPlug
        holder.content.itemProfileOnPlugTv.text =
            if (profile.accConfig.configOnPlug == null) "-"
            else profile.accConfig.getOnPlug(mContext)

        holder.content.itemProfilePrioritizeBatteryIdleTv.isVisible = profile.accConfig.prioritizeBatteryIdleMode
        holder.content.itemProfileResetBsOnPauseTv.isVisible = profile.accConfig.configResetBsOnPause
        holder.content.itemProfileResettUnpluggedTv.isVisible = profile.accConfig.configResetUnplugged

        holder.content.itemProfileOptionsIb.setOnClickListener {

            with(PopupMenu(mContext, holder.content.itemProfileOptionsIb))
            {
                menuInflater.inflate(R.menu.profiles_options_menu, this.menu)

                setOnMenuItemClickListener {
                    // Resolve the live position; the captured one can be stale after
                    // a list change. getOrNull guards against an out-of-bounds access.
                    val profileItem = mProfilesList.getOrNull(holder.adapterPosition)
                        ?: mProfilesList.getOrNull(position)
                        ?: return@setOnMenuItemClickListener true
                    when (it.itemId)
                    {
                        R.id.profile_option_menu_edit -> mListener.editProfile(profileItem)
                        R.id.profile_option_menu_rename -> mListener.renameProfile(profileItem)
                        R.id.profile_option_menu_delete -> mListener.deleteProfile(profileItem)
                    }
                    true
                }

                show()
            }
        }

        // Make visible or Hide the selectedView
        holder.content.itemProfileSelectedIndicatorView.isVisible = profile.uid == mActiveProfileId
    }

    internal fun setActiveProfile(id: Int)
    {
        mActiveProfileId = id
        notifyDataSetChanged()
    }

    internal fun setProfiles(profiles: List<AccaProfile>)
    {
        mProfilesList = profiles
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mProfilesList.size

    fun getProfileAt(pos: Int): AccaProfile? = mProfilesList.getOrNull(pos)

    /**
     * Set the OnProfileClickListener, the parent must implement the interface.
     */
    fun setOnClickListener(profileClickListener: OnProfileClickListener)
    {
        mListener = profileClickListener
    }
}
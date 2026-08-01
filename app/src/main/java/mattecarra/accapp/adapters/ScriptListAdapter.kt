package mattecarra.accapp.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.preference.PreferenceManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import mattecarra.accapp.R
import mattecarra.accapp._interface.OnScriptClickListener
import mattecarra.accapp.models.AccaScript

class ScriptListAdapter internal constructor(context: Context) : RecyclerView.Adapter<ScriptListAdapter.ScriptViewHolder>()
{
    private val mInflater: LayoutInflater = LayoutInflater.from(context)
    private var mScriptsList = emptyList<AccaScript>()
    private lateinit var mListener: OnScriptClickListener
    private val mContext = context

    // Which scripts the user has opened. Keyed by uid, not position, so it survives a
    // reorder or a LiveData refresh. Everything starts collapsed: a long Description or
    // Shell text used to take the whole screen, making a list of scripts unscannable.
    private val mExpanded = mutableSetOf<Int>()

    inner class ScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    {
        val titleTv: TextView = itemView.findViewById(R.id.item_script_title_tv)
        val descriptionTv: TextView = itemView.findViewById(R.id.item_script_description_tv)
        val bodyTv: TextView = itemView.findViewById(R.id.item_script_body_tv)
        val optionsIb: ImageButton = itemView.findViewById(R.id.item_script_options_ib)
        val expandIb: ImageButton = itemView.findViewById(R.id.item_script_expand_ib)
        val runIb: ImageButton = itemView.findViewById(R.id.item_script_run_ib)

        init
        {
            // Running a script is deliberate, so it needs a deliberate target.
            //
            // The whole row used to be the run trigger. Scripts execute root shell commands
            // against a live charging controller, and users reported firing one or two by
            // accident on a glancing tap while trying to scroll the list. A RecyclerView treats a
            // touch that moves less than the scroll slop as a click, so "tried to scroll, ran a
            // script" is not user error - it is the row being the wrong control.
            //
            // Run now lives on its own button and on the existing overflow menu action, both of
            // which have to be aimed at. The row itself expands/collapses the body, which is the
            // harmless thing a stray tap should do.
            // adapterPosition can be NO_POSITION (-1) on a stale click; getOrNull prevents an
            // IndexOutOfBounds crash. Same expand/collapse behaviour as the chevron, so a stray
            // tap does the harmless thing.
            itemView.setOnClickListener {
                val s = mScriptsList.getOrNull(adapterPosition) ?: return@setOnClickListener
                if (!mExpanded.remove(s.uid)) mExpanded.add(s.uid)
                notifyItemChanged(adapterPosition)
            }
            runIb.setOnClickListener {
                mScriptsList.getOrNull(adapterPosition)?.let { s -> mListener.onScriptClick(s) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder
    {
        val itemView = mInflater.inflate(R.layout.script_item, parent, false)
        return ScriptViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int)
    {
        val script = mScriptsList[position]

        holder.titleTv.text = script.scName
        holder.descriptionTv.text = script.scDescription
        holder.bodyTv.text = script.scBody

        holder.descriptionTv.visibility = if (script.scDescription.trim().isEmpty()) View.GONE else View.VISIBLE

        // Collapsed shows enough to recognise the script; expanded shows all of it. maxLines
        // has to be set on BOTH paths, never left to the layout, because holders are recycled
        // and would otherwise inherit the previous row's state.
        val isExpanded = mExpanded.contains(script.uid)
        if (isExpanded) {
            holder.descriptionTv.maxLines = Int.MAX_VALUE
            holder.bodyTv.maxLines = Int.MAX_VALUE
        } else {
            holder.descriptionTv.maxLines = 1
            holder.bodyTv.maxLines = 2
        }
        holder.descriptionTv.ellipsize = TextUtils.TruncateAt.END
        holder.bodyTv.ellipsize = TextUtils.TruncateAt.END
        // Reuses the app's existing drop-up/drop-down pair rather than adding a new icon style.
        holder.expandIb.setImageResource(
            if (isExpanded) R.drawable.ic_baseline_arrow_drop_up_24px else R.drawable.ic_baseline_arrow_drop_down_24px
        )
        holder.expandIb.contentDescription =
            mContext.getString(if (isExpanded) R.string.script_collapse else R.string.script_expand)

        // Only worth offering when there is something hidden. A one-line script with no
        // description has nothing to expand, and a dead chevron is worse than no chevron.
        val hasMore = script.scDescription.trim().isNotEmpty() || script.scBody.length > 40 || script.scBody.contains('\n')
        holder.expandIb.visibility = if (hasMore) View.VISIBLE else View.INVISIBLE

        holder.expandIb.setOnClickListener {
            val s = mScriptsList.getOrNull(holder.adapterPosition) ?: return@setOnClickListener
            if (!mExpanded.remove(s.uid)) mExpanded.add(s.uid)
            notifyItemChanged(holder.adapterPosition)
        }

        holder.optionsIb.setOnClickListener {
            with(PopupMenu(mContext, holder.optionsIb)) {
                menuInflater.inflate(R.menu.scripts_options_menu, this.menu)

                // Gate Edit (which lets the user replace the script body and run arbitrary
                // root shell) behind the "Allow custom shell scripts" preference. Off by
                // default; Rename/Copy/Delete + Run remain available either way.
                val allowCustomScripts = PreferenceManager.getDefaultSharedPreferences(mContext)
                    .getBoolean("pref_allow_custom_scripts", false)
                this.menu.findItem(R.id.script_option_menu_edit)?.isVisible = allowCustomScripts

                setOnMenuItemClickListener {
                    // Resolve the live position; the captured one can be stale.
                    val scriptItem = mScriptsList.getOrNull(holder.adapterPosition)
                        ?: mScriptsList.getOrNull(position)
                        ?: return@setOnMenuItemClickListener true
                    when (it.itemId)
                    {
                        R.id.script_option_menu_run -> mListener.onScriptClick(scriptItem)
                        R.id.script_option_menu_run_silent -> mListener.onScriptRunSilent(scriptItem)
                        R.id.script_option_menu_edit -> mListener.onEditScript(scriptItem)
                        R.id.script_option_menu_copy -> mListener.onCopyScript(scriptItem)
                        R.id.script_option_menu_rename -> mListener.onRenameScript(scriptItem)
                        R.id.script_option_menu_delete -> mListener.onDeleteScript(scriptItem)
                        R.id.script_option_menu_move_up -> mListener.onMoveScript(scriptItem, true)
                        R.id.script_option_menu_move_down -> mListener.onMoveScript(scriptItem, false)
                    }
                    true
                }

                // Drag is the fast way to reorder, but it is not reachable with a screen reader
                // or one-handed on a long list, so the same move is offered here. Hide the
                // direction that cannot go anywhere rather than letting it no-op silently.
                val pos = holder.adapterPosition
                this.menu.findItem(R.id.script_option_menu_move_up)?.isVisible = pos > 0
                this.menu.findItem(R.id.script_option_menu_move_down)?.isVisible =
                    pos != RecyclerView.NO_POSITION && pos < mScriptsList.size - 1

                show()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun setScripts(scripts: List<AccaScript>)
    {
        mScriptsList = scripts
        // Forget expansion state for scripts that no longer exist, so the set cannot grow
        // without bound as scripts are created and deleted over a long session.
        val live = scripts.mapTo(HashSet()) { it.uid }
        mExpanded.retainAll(live)
        notifyDataSetChanged()
    }

    /**
     * Move a row during a drag. Only the on-screen list is touched here and only
     * notifyItemMoved is emitted, so the row follows the finger without the list being
     * rebuilt underneath it. The new order is written to the database once, on drop.
     */
    fun onItemMove(from: Int, to: Int): Boolean
    {
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        if (from !in mScriptsList.indices || to !in mScriptsList.indices) return false
        val mutable = mScriptsList.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        mScriptsList = mutable
        notifyItemMoved(from, to)
        return true
    }

    /** The list exactly as it now appears, for persisting after a move. */
    fun currentOrder(): List<AccaScript> = mScriptsList.toList()

    fun positionOf(script: AccaScript): Int = mScriptsList.indexOfFirst { it.uid == script.uid }

    override fun getItemCount(): Int
    {
        return mScriptsList.size
    }

    fun getScriptAt(pos: Int): AccaScript?
    {
        return mScriptsList.getOrNull(pos)
    }

    fun setOnClickListener(scriptClickListener: OnScriptClickListener)
    {
        mListener = scriptClickListener
    }
}
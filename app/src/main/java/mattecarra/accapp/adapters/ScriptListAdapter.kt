package mattecarra.accapp.adapters

import android.annotation.SuppressLint
import android.content.Context
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

    inner class ScriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    {
        val titleTv: TextView = itemView.findViewById(R.id.item_script_title_tv)
        val descriptionTv: TextView = itemView.findViewById(R.id.item_script_description_tv)
        val bodyTv: TextView = itemView.findViewById(R.id.item_script_body_tv)
        val optionsIb: ImageButton = itemView.findViewById(R.id.item_script_options_ib)

        init
        {
            // adapterPosition can be NO_POSITION (-1) on a stale click; getOrNull
            // prevents an IndexOutOfBounds crash.
            itemView.setOnClickListener { mScriptsList.getOrNull(adapterPosition)?.let { s -> mListener.onScriptClick(s) } }
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

        holder.optionsIb.setOnClickListener {
            with(PopupMenu(mContext, holder.optionsIb)) {
                menuInflater.inflate(R.menu.scripts_options_menu, this.menu)

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
                    }
                    true
                }

                show()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun setScripts(scripts: List<AccaScript>)
    {
        mScriptsList = scripts
        notifyDataSetChanged()
    }

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
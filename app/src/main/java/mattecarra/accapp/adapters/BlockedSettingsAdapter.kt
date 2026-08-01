package mattecarra.accapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import mattecarra.accapp.R

/**
 * One blocked charge-control node per row. Nothing here writes: the activity owns every
 * mutation so the on-disk list and the list on screen can never disagree.
 *
 * @param node   full sysfs path, which is what the engine matches on
 * @param label  the short name shown first, because that is what people recognise
 * @param source where the block came from: an AMPS crash, ACC's probe, or the user
 * @param editable ACC's probe list stores whole "node on off" rows, so editing one by path
 *                 would corrupt it. Those rows are removable but not editable.
 */
data class BlockedItem(
    val node: String,
    val label: String,
    val source: String,
    val editable: Boolean
)

class BlockedSettingsAdapter(
    private val onToggle: (Int) -> Unit,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<BlockedSettingsAdapter.Holder>() {

    private var items: List<BlockedItem> = emptyList()
    val selected: MutableSet<Int> = LinkedHashSet()

    fun submit(newItems: List<BlockedItem>) {
        items = newItems
        selected.clear()
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): BlockedItem = items[position]
    fun selectedItems(): List<BlockedItem> = selected.sorted().map { items[it] }

    fun toggle(position: Int) {
        if (!selected.add(position)) selected.remove(position)
        notifyItemChanged(position)
    }

    fun selectAll() {
        if (selected.size == items.size) selected.clear()
        else { selected.clear(); items.indices.forEach { selected.add(it) } }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_blocked, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.source.text = item.source
        holder.check.visibility = if (selected.contains(position)) View.VISIBLE else View.GONE
        holder.edit.visibility = if (item.editable) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onToggle(holder.adapterPosition) }
        holder.itemView.setOnLongClickListener { onToggle(holder.adapterPosition); true }
        holder.edit.setOnClickListener { onEdit(holder.adapterPosition) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val check: ImageView = view.findViewById(R.id.blocked_check)
        val name: TextView = view.findViewById(R.id.blocked_name)
        val source: TextView = view.findViewById(R.id.blocked_source)
        val edit: ImageButton = view.findViewById(R.id.blocked_edit)
    }
}

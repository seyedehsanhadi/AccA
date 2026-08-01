package mattecarra.accapp.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.adapters.BlockedItem
import mattecarra.accapp.adapters.BlockedSettingsAdapter
import mattecarra.accapp.databinding.ActivityBlockedSettingsBinding
import kotlin.coroutines.CoroutineContext

/**
 * Blocked charge-control settings.
 *
 * These are nodes nothing may write, because writing one previously took the phone down during
 * a switch scan. The whole point of the list is that ACC and AMPS skip them, so this screen has
 * to make the list fully inspectable and editable rather than a one-way dialog: an entry that
 * cannot be examined or corrected is an entry people work around by clearing everything.
 *
 * Built from the same parts as the rest of AccA (toolbar style, card geometry, accent colour) so
 * it does not read as a different app. The only thing marking it out is a warning banner and a
 * thin rail per row, and every destructive action names what it will allow to happen again.
 */
class BlockedSettingsActivity : AppCompatActivity(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private lateinit var binding: ActivityBlockedSettingsBinding
    private lateinit var adapter: BlockedSettingsAdapter

    companion object {
        // Both lists live in the data dir so they survive a reboot and a module upgrade.
        private const val DATA_DIR = "/data/adb/vr25/acc-data"
        // The module's own engine copy, present whenever the module is installed. A scan-time
        // copy under /data/local/tmp only exists after a scan, so it cannot be relied on.
        private const val ENGINE = "/data/adb/vr25/acc/acc-compat.sh"
        private const val PSY = "/sys/class/power_supply"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.blockedToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = BlockedSettingsAdapter(
            onToggle = { pos -> if (pos >= 0) { adapter.toggle(pos); updateSubtitle(adapter.itemCount); invalidateOptionsMenu() } },
            onEdit = { pos -> if (pos >= 0) promptEdit(adapter.itemAt(pos)) }
        )
        binding.blockedRv.layoutManager = LinearLayoutManager(this)
        binding.blockedRv.adapter = adapter
        binding.blockedFab.setOnClickListener { promptAdd() }

        reload()
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.blocked_settings_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val any = adapter.selected.isNotEmpty()
        menu.findItem(R.id.blocked_menu_remove)?.isVisible = any
        menu.findItem(R.id.blocked_menu_select_all)?.isVisible = adapter.itemCount > 0
        menu.findItem(R.id.blocked_menu_clear)?.isVisible = adapter.itemCount > 0 && !any
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.blocked_menu_select_all -> { adapter.selectAll(); updateSubtitle(adapter.itemCount); invalidateOptionsMenu(); return true }
            R.id.blocked_menu_remove -> { confirmRemoveSelected(); return true }
            R.id.blocked_menu_clear -> { confirmClearAll(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- read

    private fun reload() = launch {
        // "no root" and "nothing blocked" look identical from the list alone, and telling
        // someone their list is empty when it could not be read is worse than saying nothing:
        // they conclude they are protected. Reinstalling the app drops its root grant, so this
        // is the state a returning user actually lands in.
        val rooted = withContext(Dispatchers.IO) { Shell.rootAccess() }
        val items = if (rooted) withContext(Dispatchers.IO) { readBlocked() } else emptyList()
        adapter.submit(items)
        val empty = items.isEmpty()
        binding.blockedEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.blockedRv.visibility = if (empty) View.GONE else View.VISIBLE
        binding.blockedEmpty.setText(if (rooted) R.string.blocked_none else R.string.blocked_noroot)
        binding.blockedBannerText.setText(when {
            !rooted -> R.string.blocked_noroot_banner
            empty -> R.string.blocked_none_banner
            else -> R.string.blocked_intro
        })
        binding.blockedFab.isEnabled = rooted
        updateSubtitle(items.size)
        invalidateOptionsMenu()
    }

    /** Selection count wins over the total: while selecting, that is the number being acted on. */
    private fun updateSubtitle(total: Int) {
        val sel = adapter.selected.size
        supportActionBar?.subtitle = when {
            sel > 0 -> getString(R.string.blocked_selected_n, sel)
            total > 0 -> resources.getQuantityString(R.plurals.blocked_count, total, total)
            else -> null
        }
    }

    private fun readBlocked(): List<BlockedItem> {
        if (!Shell.rootAccess()) return emptyList()
        val out = ArrayList<BlockedItem>()
        // AMPS crash blacklist. Since engine 7.2.1 a line is "path<TAB>value<TAB>when", where
        // value is what was being written when the phone went down (it can be empty). Older
        // entries are a bare path with no tabs, so split and take field 1 either way: reading
        // the whole line as the path silently broke every action on this screen, because the
        // node it then passed to the engine did not exist.
        Shell.su("cat $DATA_DIR/.acc-compat-blacklist 2>/dev/null").exec().out
            .map { it.trimEnd() }.filter { it.isNotBlank() }
            .forEach { line ->
                val f = line.split('\t')
                val node = f[0].trim()
                if (node.isNotEmpty()) {
                    // Terse on purpose: the switch, its short path, and the value it was
                    // writing. Why anything is on this list is said once in the banner, not
                    // retold on every row.
                    val value = f.getOrNull(1)?.trim().orEmpty()
                    val short = node.removePrefix("$PSY/")
                    val detail = if (value.isNotEmpty()) "$short  →  $value" else short
                    out.add(BlockedItem(node, node.substringAfterLast('/'), detail, true))
                }
            }
        // ACC probe blacklist: whole "node on off --" rows. Removable, not editable by path.
        Shell.su("cat $DATA_DIR/.probe-blacklist 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val node = line.substringBefore(' ')
                if (node.isNotEmpty() && out.none { e -> e.node.endsWith(node) })
                    out.add(BlockedItem(node, node.substringAfterLast('/'),
                        node.removePrefix("$PSY/"), false))
            }
        return out
    }

    /** Real nodes on THIS phone, so adding one is a choice from a list rather than a guess. */
    private fun scanCandidates(): List<String> {
        if (!Shell.rootAccess()) return emptyList()
        val cmd = "ls -d $PSY/*/ 2>/dev/null | while read d; do " +
                  "for n in charging_enabled input_suspend battery_charging_enabled charge_disable " +
                  "charge_control_limit charging_enable store_mode op_disable_charge mmi_charging_enable " +
                  "night_charging slate_mode current_max constant_charge_current_max; do " +
                  "[ -f \"\$d\$n\" ] && echo \"\$d\$n\"; done; done | sed 's|//|/|g' | sort -u"
        return Shell.su(cmd).exec().out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- add

    private fun promptAdd() {
        MaterialDialog(this).show {
            title(R.string.blocked_add_title)
            message(R.string.blocked_add_choose)
            positiveButton(R.string.blocked_add_pick) { pickFromPhone() }
            neutralButton(R.string.blocked_add_type) { typeManually() }
            negativeButton(android.R.string.cancel)
        }
    }

    private fun pickFromPhone() = launch {
        val found = withContext(Dispatchers.IO) { scanCandidates() }
        if (found.isEmpty()) { typeManually(); return@launch }
        val already = adapter.let { a -> (0 until a.itemCount).map { a.itemAt(it).node }.toSet() }
        val labels = found.map { p ->
            val short = p.removePrefix("$PSY/")
            if (already.any { it.endsWith(short) || it == p }) "$short  ${getString(R.string.blocked_already)}" else short
        }
        MaterialDialog(this@BlockedSettingsActivity).show {
            title(R.string.blocked_pick_title)
            message(R.string.blocked_pick_body)
            listItems(items = labels) { _, index, _ -> doAdd(found[index]) }
            neutralButton(R.string.blocked_add_type) { typeManually() }
            negativeButton(android.R.string.cancel)
        }
    }

    private fun typeManually() {
        MaterialDialog(this).show {
            title(R.string.blocked_add_title)
            message(R.string.blocked_add_body)
            input(hintRes = R.string.blocked_add_hint, waitForPositiveButton = true) { _, text ->
                val raw = text.toString().trim()
                if (raw.isNotEmpty()) doAdd(if (raw.startsWith("/")) raw else "$PSY/$raw")
            }
            negativeButton(android.R.string.cancel)
        }
    }

    private fun doAdd(node: String) = launch {
        // Confirm against the list, not the exit code: the engine's --blacklist arm ends in
        // exit 0 even when the write did not land (read-only or full /data), so isSuccess would
        // report "Added" over a list that never gained the entry.
        val ok = withContext(Dispatchers.IO) {
            Shell.su("sh $ENGINE --blacklist add '$node'").exec()
            readBlocked().any { it.node == node }
        }
        toast(if (ok) R.string.blocked_added else R.string.blocked_add_failed)
        if (ok) reload()
    }

    // ---------------------------------------------------------------- edit

    private fun promptEdit(item: BlockedItem) {
        MaterialDialog(this).show {
            title(R.string.blocked_edit_title)
            message(R.string.blocked_edit_body)
            input(hint = null, prefill = item.node, waitForPositiveButton = true) { _, text ->
                val raw = text.toString().trim()
                if (raw.isEmpty() || raw == item.node) return@input
                val node = if (raw.startsWith("/")) raw else "$PSY/$raw"
                launch {
                    // Replace, not append: remove the old entry only after the new one lands, so a
                    // failure halfway cannot leave the node unblocked.
                    val ok = withContext(Dispatchers.IO) {
                        Shell.su("sh $ENGINE --blacklist add '$node'").exec()
                        val added = readBlocked().any { it.node == node }
                        if (added) {
                            Shell.su("sh $ENGINE --blacklist rm '${item.node}'").exec()
                            readBlocked().none { it.node == item.node }
                        } else false
                    }
                    toast(if (ok) R.string.blocked_edited else R.string.blocked_add_failed)
                    if (ok) reload()
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    // ---------------------------------------------------------------- remove

    private fun confirmRemoveSelected() {
        val picked = adapter.selectedItems()
        if (picked.isEmpty()) return
        MaterialDialog(this).show {
            title(R.string.blocked_remove_title)
            message(text = getString(R.string.blocked_remove_many,
                picked.size, picked.joinToString("\n") { "• ${it.label}" }))
            positiveButton(R.string.blocked_remove_confirm) {
                launch {
                    val ok = withContext(Dispatchers.IO) { picked.all { unblock(it) } }
                    toast(if (ok) R.string.blocked_removed else R.string.blocked_remove_failed)
                    reload()
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    private fun unblock(item: BlockedItem): Boolean {
        if (!Shell.rootAccess()) return false
        // The engine owns its own list; ACC's probe rows are a foreign format the engine never
        // rewrites, so those are edited directly. Same split the CLI uses.
        //
        // Discriminate on `editable`, which readBlocked() sets structurally: false means the row
        // came from ACC's probe list. The previous test compared `source` against a localized
        // string that readBlocked() never puts there (it holds a path), so it was always false
        // and every probe row took the engine path, which cannot remove one. The engine printed
        // "removed" and exited 0 while the row stayed blocked, with no way to clear it from here.
        if (!item.editable) {
            Shell.su("f=$DATA_DIR/.probe-blacklist; [ -f \"\$f\" ] || exit 1; " +
                     "grep -v \"^${item.node} \" \"\$f\" > \"\$f.t\" 2>/dev/null || :; " +
                     "mv -f \"\$f.t\" \"\$f\" 2>/dev/null; sync").exec()
        } else {
            Shell.su("sh $ENGINE --blacklist rm '${item.node}'").exec()
        }
        // Confirm against the list itself. The engine's `--blacklist` arm ends in `exit 0` on
        // paths that changed nothing, so isSuccess is not evidence the node was unblocked, and
        // reporting it as one is how "Removed" appeared over a row that was still there.
        return readBlocked().none { it.node == item.node }
    }

    private fun confirmClearAll() {
        val n = adapter.itemCount
        MaterialDialog(this).show {
            title(R.string.blocked_clear_title)
            message(text = getString(R.string.blocked_clear_body, n))
            positiveButton(R.string.blocked_clear_confirm) {
                launch {
                    val ok = withContext(Dispatchers.IO) {
                        Shell.su("sh $ENGINE --blacklist clear").exec()
                        Shell.su("f=$DATA_DIR/.probe-blacklist; [ -f \"\$f\" ] && : > \"\$f\"; sync").exec()
                        readBlocked().isEmpty()
                    }
                    toast(if (ok) R.string.blocked_cleared else R.string.blocked_remove_failed)
                    reload()
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()
}

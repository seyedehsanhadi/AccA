package mattecarra.accapp.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import mattecarra.accapp.R
import mattecarra.accapp.adapters.ScriptEntriesAdapter
import mattecarra.accapp.databinding.ActivityExportBinding
import mattecarra.accapp.models.AccaScript
import mattecarra.accapp.models.ScriptEntry
import mattecarra.accapp.utils.ExportStore
import mattecarra.accapp.utils.LogExt

/**
 * Export scripts as JSON via the system share sheet.
 *
 * Scripts previously had no way out of the app at all, so a reinstall lost everything the user
 * had written. Deliberately the same flow, layout and JSON shape profiles already use, so there
 * is nothing new to learn and the output can be pasted back through Import.
 */
class ExportScriptsActivity: AppCompatActivity() {
    private lateinit var mAdapter: ScriptEntriesAdapter

    override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        val binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.exportToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.export_scripts_title)

        mAdapter = ScriptEntriesAdapter()
        val exportRecycler: RecyclerView = findViewById(R.id.export_entries_rv)
        exportRecycler.layoutManager = LinearLayoutManager(this)
        exportRecycler.adapter = mAdapter
        exportRecycler.isNestedScrollingEnabled = true

        // Safe-cast: a missing or garbled extra yields an empty list rather than a crash.
        @Suppress("UNCHECKED_CAST")
        val scripts = (intent.getSerializableExtra("list") as? ArrayList<AccaScript>) ?: arrayListOf()
        for (script in scripts) mAdapter.addEntry(ScriptEntry(script))

        // Backing up everything is the reason people come here, so start with all selected
        // instead of making them tick a long list one row at a time.
        mAdapter.checkAll()

        val fab: ExtendedFloatingActionButton = findViewById(R.id.export_fab)
        // The blue button SAVES. It used to open the share sheet, which is a different question
        // from the one the user came here to answer: they want the file, not a list of apps to
        // hand it to. Sharing is still one tap away in the toolbar.
        fab.setText(R.string.export_save_action)
        fab.setOnClickListener { saveToAccaFolder() }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.export_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            // Unticking a long list one row at a time was the only way to export a couple of
            // scripts, which is both tedious and how a half-selected state goes unnoticed.
            R.id.export_select_all_menu -> mAdapter.checkAll()
            R.id.export_deselect_all_menu -> mAdapter.uncheckAll()
            // Three ways out, mirroring the three ways in on the Import screen. Share hands the
            // file to another app; Save to file puts it wherever the user says through the system
            // picker, including a real folder they can find later; Copy to clipboard is the quick
            // route for a couple of scripts and pairs with Import's own clipboard option.
            R.id.export_share_menu -> returnSelectedEntries()
            R.id.export_save_as_menu -> saveToFile()
            R.id.export_copy_clipboard_menu -> copyToClipboard()
        }
        return super.onOptionsItemSelected(item)
    }

    /** The JSON and the filename for whatever is currently ticked, or null if nothing is. */
    private fun buildExport(): Pair<String, String>? {
        val checkedEntries = mAdapter.getCheckedEntries()
        if (checkedEntries.isEmpty()) {
            Toast.makeText(applicationContext, R.string.export_none_selected, Toast.LENGTH_SHORT).show()
            return null
        }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val listType = Types.newParameterizedType(List::class.java, AccaScript::class.java)
        val jsonAdapter: JsonAdapter<List<AccaScript>> = moshi.adapter(listType)
        // Export the scripts themselves, not the picker wrapper, so the JSON stays a plain
        // readable list a user can eyeball or hand-edit before importing it back.
        val json = jsonAdapter.toJson(checkedEntries.map { it.script })
        val name = if (checkedEntries.size == 1)
                "acca-script-" + checkedEntries[0].script.scName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48) + ".json"
            else
                "acca-scripts-" + checkedEntries.size + ".json"
        return Pair(name, json)
    }

    private val createDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument()
    ) { uri: Uri? ->
        val json = pendingJson ?: return@registerForActivityResult
        pendingJson = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw java.io.IOException("no output stream for $uri")
            Toast.makeText(this, getString(R.string.export_saved_toast, pendingCount), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogExt().e(javaClass.simpleName, "save to file failed: ${e.message}")
            Toast.makeText(this, R.string.export_write_failed, Toast.LENGTH_LONG).show()
        }
    }
    private var pendingJson: String? = null
    private var pendingCount = 0

    /**
     * The primary action: write the file where Import will find it without anyone having to
     * navigate anywhere. See ExportStore for why AccA owns a folder rather than leaning on the
     * system picker, which is broken on at least one of the test devices.
     */
    private fun saveToAccaFolder() {
        val (name, json) = buildExport() ?: return
        val count = mAdapter.getCheckedEntries().size
        val path = ExportStore.save(filesDir, name, json)
        if (path == null) {
            Toast.makeText(this, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.export_saved_to, count, ExportStore.DIR_LABEL + "/" + ExportStore.displayName(path)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun saveToFile() {
        val (name, json) = buildExport() ?: return
        pendingJson = json
        pendingCount = mAdapter.getCheckedEntries().size
        createDoc.launch(name)
    }

    private fun copyToClipboard() {
        val (name, json) = buildExport() ?: return
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(name, json))
        Toast.makeText(this, getString(R.string.export_copied_toast, mAdapter.getCheckedEntries().size), Toast.LENGTH_SHORT).show()
    }

    private fun returnSelectedEntries() {
        val (name, result) = buildExport() ?: return

        // Share a FILE, not EXTRA_TEXT.
        //
        // EXTRA_TEXT travels through the 1MB Binder transaction buffer, and receiving apps
        // routinely truncate long text far below that. Field report: exporting 4-5 scripts
        // produced 3 files with their contents run together, and exporting 3 produced a single
        // file holding all three - which is what a JSON array looks like after it has been
        // truncated and re-parsed by whatever was on the other end of the share sheet.
        //
        // A file has none of that: the bytes are written by us, the name is ours, and the
        // receiving app copies rather than re-interprets. It also gives the user the
        // [name].json they expected instead of an untitled text blob.
        val file = try {
            java.io.File(java.io.File(filesDir, "exports").apply { mkdirs() }, name).apply {
                writeText(result)
            }
        } catch (e: Exception) {
            LogExt().e(javaClass.simpleName, "export write failed: ${e.message}")
            Toast.makeText(this, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        // Assert what we wrote actually landed. A silent short write would hand the user a
        // file that imports as fewer scripts than they selected - the very failure being fixed.
        if (!file.exists() || file.length() < result.length.toLong()) {
            LogExt().e(javaClass.simpleName, "export short write: ${file.length()} of ${result.length}")
            Toast.makeText(this, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, getString(R.string.export_count_toast, mAdapter.getCheckedEntries().size), Toast.LENGTH_SHORT).show()
        startActivity(Intent.createChooser(sendIntent, null))
    }
}

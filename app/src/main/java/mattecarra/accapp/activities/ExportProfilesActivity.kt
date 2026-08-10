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
import mattecarra.accapp.adapters.ProfileEntriesAdapter
import mattecarra.accapp.databinding.ActivityExportBinding
import mattecarra.accapp.models.ProfileEntry
import mattecarra.accapp.utils.ExportStore
import mattecarra.accapp.utils.LogExt

class ExportProfilesActivity: AppCompatActivity() {
    private lateinit var mAdapter: ProfileEntriesAdapter
    private lateinit var mEntries: List<ProfileEntry>

    override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        val binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.exportToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The scripts exporter names itself; this one showed a bare "Export" and left the user to
        // infer what they were exporting. Same screen, same job, same title treatment.
        supportActionBar?.title = getString(R.string.export_profiles_title)

        mAdapter = ProfileEntriesAdapter()
        var exportRecycler: RecyclerView = findViewById(R.id.export_entries_rv)
        var layoutManager = LinearLayoutManager(this)

        // Read from intent and deserialise. Safe-cast so a missing/garbled extra
        // yields an empty list instead of crashing the activity on open.
        @Suppress("UNCHECKED_CAST")
        mEntries = (intent.getSerializableExtra("list") as? List<ProfileEntry>) ?: emptyList()

        exportRecycler.layoutManager = layoutManager
        exportRecycler.adapter = mAdapter
        exportRecycler.isNestedScrollingEnabled = true

        for (entry: ProfileEntry in mEntries) {
            mAdapter.addEntry(entry)
        }

        // Backing up everything is why people open this screen, so start with all selected --
        // matching the scripts exporter rather than making them tick a list first.
        mAdapter.checkAll()

        var fab: ExtendedFloatingActionButton = findViewById(R.id.export_fab)
        // The blue button SAVES; sharing moved to the toolbar. Same reasoning as the script
        // exporter, and deliberately the same flow so the two screens behave alike.
        fab.setText(R.string.export_save_action)
        fab.setOnClickListener { saveToAccaFolder() }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.export_profiles_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            // Three ways out, mirroring the three ways in on the Import screen.
            R.id.export_share_menu -> returnSelectedEntries()
            R.id.export_select_all_menu -> mAdapter.checkAll()
            R.id.export_deselect_all_menu -> mAdapter.uncheckAll()
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
        val listType = Types.newParameterizedType(List::class.java, ProfileEntry::class.java)
        val jsonAdapter: JsonAdapter<List<ProfileEntry>> = moshi.adapter(listType)
        val json = jsonAdapter.toJson(checkedEntries)
        val name = if (checkedEntries.size == 1)
                "acca-profile-" + checkedEntries[0].profile.profileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48) + ".json"
            else
                "acca-profiles-" + checkedEntries.size + ".json"
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

        // Share a FILE, not EXTRA_TEXT - same defect and same fix as the script exporter.
        // EXTRA_TEXT crosses the Binder transaction buffer and receiving apps truncate long
        // text, which turns a JSON array of several profiles into a shorter, malformed one.
        val file = try {
            java.io.File(java.io.File(filesDir, "exports").apply { mkdirs() }, name).apply {
                writeText(result)
            }
        } catch (e: Exception) {
            Toast.makeText(applicationContext, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (!file.exists() || file.length() < result.length.toLong()) {
            Toast.makeText(applicationContext, R.string.export_write_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, null))
    }
}

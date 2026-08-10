package mattecarra.accapp.activities

import android.app.Activity
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.afollestad.materialdialogs.list.listItems
import mattecarra.accapp.R
import mattecarra.accapp.adapters.ProfileEntriesAdapter
import mattecarra.accapp.databinding.ActivityImportBinding
import mattecarra.accapp.models.ProfileEntry
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.ExportStore
import mattecarra.accapp.utils.LogExt
import java.io.Serializable

class ImportProfilesActivity : AppCompatActivity() {
    private lateinit var binding : ActivityImportBinding
    private lateinit var mAdapter: ProfileEntriesAdapter

    override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.importToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mAdapter = ProfileEntriesAdapter()
        var importRecycler: RecyclerView = findViewById(R.id.import_profiles_rv)
        var layoutManager = LinearLayoutManager(this)

        importRecycler.layoutManager = layoutManager
        importRecycler.adapter = mAdapter
        importRecycler.isNestedScrollingEnabled = true

        var fab: ExtendedFloatingActionButton = findViewById(R.id.import_fab)
        fab.setOnClickListener { v -> importSelectedEntries() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.import_load_file_menu -> loadFromAccaFolder()
            R.id.import_browse_file_menu -> pickFile()
            R.id.import_load_clipboard_menu -> loadClipboard()
            R.id.import_clear_menu -> clearEntries()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_menu, menu)
        return true
    }

    /*
    Load profiles from serialized text from clipboard
     */
    /**
     * List what Export wrote and let the user tap one. This is the counterpart of the Save button
     * and the reason both live in the same folder: no navigating, and nothing that can answer
     * "Can't load content at the moment" the way the system Downloads root does on MIUI.
     * Browse... is still there for a file that came from somewhere else.
     */
    private fun loadFromAccaFolder() {
        val files = ExportStore.listOfKind("acca-profile")
        if (files.isEmpty()) {
            com.afollestad.materialdialogs.MaterialDialog(this).show {
                title(text = getString(R.string.import_pick_file_title, ExportStore.DIR_LABEL))
                message(text = getString(R.string.import_no_files_found, ExportStore.DIR_LABEL))
                positiveButton(R.string.menu_title_browse_file) { pickFile() }
                negativeButton(android.R.string.cancel)
            }
            return
        }
        val names = files.map { ExportStore.displayName(it) }
        com.afollestad.materialdialogs.MaterialDialog(this).show {
            title(text = getString(R.string.import_pick_file_title, ExportStore.DIR_LABEL))
            listItems(items = names) { dlg, index, _ ->
                val text = ExportStore.read(files[index])
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@ImportProfilesActivity, getString(R.string.import_toast_file_unreadable), Toast.LENGTH_LONG).show()
                } else if (addFromJson(text)) {
                    dlg.dismiss()
                } else {
                    Toast.makeText(this@ImportProfilesActivity, getString(R.string.import_toast_no_valid_profile_json_file), Toast.LENGTH_LONG).show()
                }
            }
            neutralButton(R.string.menu_title_browse_file) { pickFile() }
            negativeButton(android.R.string.cancel)
        }
    }

    private val pickJson =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.import_toast_file_unreadable), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (!addFromJson(text)) {
                Toast.makeText(this, getString(R.string.import_toast_no_valid_profile_json_file), Toast.LENGTH_LONG).show()
            }
        }

    // Accept anything and let the parser decide: some file managers report a generic MIME type
    // for .json, and refusing on MIME would leave the user with a file they can see but not import.
    private fun pickFile() = pickJson.launch(arrayOf("application/json", "text/plain", "*/*"))

    /**
     * Parse JSON and add whatever profiles it holds. False when nothing usable was found.
     * Shared by the clipboard and file paths so the two cannot drift apart.
     */
    /**
     * Strip a UTF-8 BOM and surrounding whitespace before parsing.
     *
     * Export shares the JSON as a file, and whichever app the user saves it with owns
     * the encoding -- and several write UTF-8 with a BOM. Moshi's reader treats that leading U+FEFF
     * as a syntax error, so a file AccA had just produced could not be read back: "No valid AccA
     * scripts in that file". Copying the same text out of that file carried the BOM to the
     * clipboard and failed identically, which is why both routes rejected valid JSON. AccA cannot
     * stop the receiving app adding it, so tolerate it here.
     */
    private fun cleanJson(text: String): String = text.trim().removePrefix("﻿").trim()

    private fun addFromJson(text: String): Boolean {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val listType = Types.newParameterizedType(List::class.java, ProfileEntry::class.java)
        val jsonAdapter: JsonAdapter<List<ProfileEntry>> = moshi.adapter(listType)
        val result = try {
            jsonAdapter.fromJson(cleanJson(text)) ?: emptyList<ProfileEntry>()
        } catch (e: Exception) { emptyList<ProfileEntry>() }
        if (result.isEmpty()) return false
        // Everything that lands arrives ticked, whichever route it came in by. Restoring a
        // backup means wanting all of it, and leaving the rows unticked makes the user select
        // the whole list again before IMPORT does anything - the same tedium Export avoids.
        for (entry: ProfileEntry in result) {
            // Parity with ConfigConverter.toConfigTemperature: a profile exported before the
            // shutdown_temp field existed imports with shutdown=0, which validateConfig would
            // reject. Restore ACC's default so an imported legacy profile stays saveable.
            if (entry.profile.accConfig.configTemperature.shutdown <= 0)
                entry.profile.accConfig.configTemperature.shutdown = 55
            mAdapter.addEntry(entry)
        }
        if (mAdapter.itemCount > 0) {
            binding.importProfilesRv.visibility = View.VISIBLE
            binding.importProfileEmptyTv.visibility = View.GONE
        }
        mAdapter.checkAll()
        return true
    }

    fun loadClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val ok = if (clipboard.hasPrimaryClip() &&
            clipboard.primaryClipDescription?.hasMimeType(MIMETYPE_TEXT_PLAIN) == true) {
            addFromJson(clipboard.primaryClip?.getItemAt(0)?.text.toString())
        } else false
        if (!ok) {
            Toast.makeText(this, getString(R.string.import_toast_no_valid_profile_json_clipboard), Toast.LENGTH_LONG).show()
        }
    }

    fun clearEntries() {
        mAdapter.clearEntries()
        binding.importProfilesRv.visibility = View.GONE
        binding.importProfileEmptyTv.visibility = View.VISIBLE
    }

    fun importSelectedEntries() {
        val checkedEntries = mAdapter.getCheckedEntries()
        if (checkedEntries.isNotEmpty()) {
            val returnIntent = Intent()
            returnIntent.putExtra(Constants.DATA_KEY, checkedEntries as Serializable)
            setResult(Activity.RESULT_OK, returnIntent)
            finish()
        } else {
            if (mAdapter.itemCount == 0)
                Toast.makeText(this, getString(R.string.import_toast_nothing_loaded), Toast.LENGTH_LONG).show()
            else
                Toast.makeText(this, getString(R.string.import_toast_no_selected_profiles), Toast.LENGTH_LONG).show()
        }

    }
 }
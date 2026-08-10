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
import mattecarra.accapp.adapters.ScriptEntriesAdapter
import mattecarra.accapp.databinding.ActivityImportBinding
import mattecarra.accapp.models.AccaScript
import mattecarra.accapp.models.ScriptEntry
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.ExportStore
import mattecarra.accapp.utils.LogExt
import java.io.Serializable

/**
 * Import scripts from JSON, either from a file or from the clipboard.
 *
 * File import was missing entirely: Export writes a .json to a folder of the user's choosing, and
 * Import could only read the clipboard, so an exported file could never be imported back. Reported
 * from the field -- "it doesn't seem to let me pick a file, but only accepts JSON in my clipboard".
 */
class ImportScriptsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImportBinding
    private lateinit var mAdapter: ScriptEntriesAdapter

    override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.importToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_scripts_title)

        mAdapter = ScriptEntriesAdapter()
        val importRecycler: RecyclerView = findViewById(R.id.import_profiles_rv)
        importRecycler.layoutManager = LinearLayoutManager(this)
        importRecycler.adapter = mAdapter
        importRecycler.isNestedScrollingEnabled = true

        val fab: ExtendedFloatingActionButton = findViewById(R.id.import_fab)
        fab.setOnClickListener { importSelectedEntries() }

        // Opening Import with the JSON already copied is a common case, so try the clipboard
        // straight away. Silent when it holds something else: both menu items are still there.
        loadClipboard(quiet = true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.import_load_file_menu -> loadFromAccaFolder()
            R.id.import_browse_file_menu -> pickFile()
            R.id.import_load_clipboard_menu -> loadClipboard(quiet = false)
            R.id.import_clear_menu -> clearEntries()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_menu, menu)
        return true
    }

    /**
     * List what Export wrote and let the user tap one. This is the counterpart of the Save button
     * and the reason both live in the same folder: no navigating, and nothing that can answer
     * "Can't load content at the moment" the way the system Downloads root does on MIUI.
     * Browse... is still there for a file that came from somewhere else.
     */
    private fun loadFromAccaFolder() {
        val files = ExportStore.listOfKind("acca-script")
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
                    Toast.makeText(this@ImportScriptsActivity, getString(R.string.import_toast_file_unreadable), Toast.LENGTH_LONG).show()
                } else if (addFromJson(text)) {
                    dlg.dismiss()
                } else {
                    Toast.makeText(this@ImportScriptsActivity, getString(R.string.import_toast_no_valid_script_json_file), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, getString(R.string.import_toast_no_valid_script_json_file), Toast.LENGTH_LONG).show()
            }
        }

    // Some file managers hand back a generic MIME type for .json, so accept anything and let the
    // parser be the judge. Refusing on MIME would put us back where we started: a file the user
    // can see but not import.
    private fun pickFile() = pickJson.launch(arrayOf("application/json", "text/plain", "*/*"))

    /**
     * Parse JSON and add whatever scripts it holds. Returns false when nothing usable was found.
     * Shared by the clipboard and file paths so the two can never drift apart.
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
        val listType = Types.newParameterizedType(List::class.java, AccaScript::class.java)
        val jsonAdapter: JsonAdapter<List<AccaScript>> = moshi.adapter(listType)
        val result = try {
            jsonAdapter.fromJson(cleanJson(text)) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (result.isEmpty()) return false
        // Everything that lands arrives ticked, whichever route it came in by. Restoring a
        // backup means wanting all of it, and leaving the rows unticked makes the user select
        // the whole list again before IMPORT does anything - the same tedium Export avoids.
        for (script in result) {
            // A script from someone else must not overwrite a local row that happens to share the
            // id: insert it as new. Order is re-assigned on insert.
            script.uid = 0
            mAdapter.addEntry(ScriptEntry(script))
        }
        if (mAdapter.itemCount > 0) {
            binding.importProfilesRv.visibility = View.VISIBLE
            binding.importProfileEmptyTv.visibility = View.GONE
        }
        mAdapter.checkAll()
        return true
    }

    private fun loadClipboard(quiet: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val ok = if (clipboard.hasPrimaryClip() &&
            clipboard.primaryClipDescription?.hasMimeType(MIMETYPE_TEXT_PLAIN) == true) {
            addFromJson(clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
        } else false
        if (!ok && !quiet) {
            Toast.makeText(this, getString(R.string.import_toast_no_valid_script_json_clipboard), Toast.LENGTH_LONG).show()
        }
    }

    private fun clearEntries() {
        mAdapter.clearEntries()
        binding.importProfilesRv.visibility = View.GONE
        binding.importProfileEmptyTv.visibility = View.VISIBLE
    }

    private fun importSelectedEntries() {
        val checkedEntries = mAdapter.getCheckedEntries()
        if (checkedEntries.isNotEmpty()) {
            val returnIntent = Intent()
            returnIntent.putExtra(Constants.DATA_KEY, ArrayList(checkedEntries.map { it.script }) as Serializable)
            setResult(Activity.RESULT_OK, returnIntent)
            finish()
        } else if (mAdapter.itemCount == 0) {
            // Telling the user to "select scripts" when the list is empty describes the wrong
            // problem - nothing could be selected because nothing was ever loaded.
            Toast.makeText(this, getString(R.string.import_toast_nothing_loaded), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.import_toast_no_selected_scripts), Toast.LENGTH_LONG).show()
        }
    }
}

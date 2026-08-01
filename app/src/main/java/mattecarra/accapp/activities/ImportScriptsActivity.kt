package mattecarra.accapp.activities

import android.app.Activity
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import mattecarra.accapp.databinding.ActivityImportBinding
import mattecarra.accapp.models.AccaScript
import mattecarra.accapp.models.ScriptEntry
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.LogExt
import java.io.Serializable

/**
 * Import scripts from JSON on the clipboard. Same flow as importing profiles.
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

        // Opening Import with the JSON already copied is the normal case, so try the clipboard
        // straight away. Silent when it holds something else: the menu item is still there.
        loadClipboard(quiet = true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.import_load_clipboard_menu -> loadClipboard(quiet = false)
            R.id.import_clear_menu -> clearEntries()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_menu, menu)
        return true
    }

    private fun loadClipboard(quiet: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(MIMETYPE_TEXT_PLAIN) == true) {
            val pasteData: String = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, AccaScript::class.java)
            val jsonAdapter: JsonAdapter<List<AccaScript>> = moshi.adapter(listType)

            try {
                // Malformed clipboard JSON must not crash: fromJson can return null or throw.
                val result = jsonAdapter.fromJson(pasteData) ?: emptyList()
                if (result.isEmpty()) {
                    if (!quiet) Toast.makeText(this, getString(R.string.import_toast_no_valid_script_json_clipboard), Toast.LENGTH_LONG).show()
                    return
                }

                for (script in result) {
                    // A script pasted from someone else must not overwrite a local row that
                    // happens to share the id: insert it as new. Order is re-assigned on insert.
                    script.uid = 0
                    mAdapter.addEntry(ScriptEntry(script))
                }

                if (mAdapter.itemCount > 0) {
                    binding.importProfilesRv.visibility = View.VISIBLE
                    binding.importProfileEmptyTv.visibility = View.GONE
                }
            } catch (e: Exception) {
                if (!quiet) Toast.makeText(this, getString(R.string.import_toast_no_valid_script_json_clipboard), Toast.LENGTH_LONG).show()
            }
        } else {
            if (!quiet) Toast.makeText(this, getString(R.string.import_toast_no_valid_script_json_clipboard), Toast.LENGTH_LONG).show()
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
        } else {
            Toast.makeText(this, getString(R.string.import_toast_no_selected_scripts), Toast.LENGTH_LONG).show()
        }
    }
}

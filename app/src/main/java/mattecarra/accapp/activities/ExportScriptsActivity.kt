package mattecarra.accapp.activities

import android.content.Intent
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
        fab.setOnClickListener { returnSelectedEntries() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun returnSelectedEntries() {
        val checkedEntries = mAdapter.getCheckedEntries()

        if (checkedEntries.isNotEmpty()) {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val listType = Types.newParameterizedType(List::class.java, AccaScript::class.java)
            val jsonAdapter: JsonAdapter<List<AccaScript>> = moshi.adapter(listType)
            // Export the scripts themselves, not the picker wrapper, so the JSON stays a plain
            // readable list a user can eyeball or hand-edit before importing it back.
            val result = jsonAdapter.toJson(checkedEntries.map { it.script })

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, result)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, null))
        } else {
            Toast.makeText(applicationContext, R.string.export_none_selected, Toast.LENGTH_SHORT).show()
        }
    }
}

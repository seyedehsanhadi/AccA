package mattecarra.accapp.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager
import mattecarra.accapp.R
import mattecarra.accapp.databinding.ActivitySettingsBinding
import mattecarra.accapp.fragments.SettingsFragment
import mattecarra.accapp.utils.LogExt

class SettingsActivity: AppCompatActivity(), FragmentManager.OnBackStackChangedListener {
    private lateinit var mSettingsFragment: SettingsFragment

    public override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            mSettingsFragment = SettingsFragment.newInstance()
            supportFragmentManager.beginTransaction().add(R.id.settings_fl, mSettingsFragment, "Settings").commit()
        } else {
            // The tagged fragment may be absent after process death; fall back to a
            // fresh instance instead of crashing on a failed cast.
            mSettingsFragment = (supportFragmentManager.findFragmentByTag("Settings") as? SettingsFragment)
                ?: SettingsFragment.newInstance().also {
                    supportFragmentManager.beginTransaction().add(R.id.settings_fl, it, "Settings").commit()
                }
        }

        supportFragmentManager.addOnBackStackChangedListener(this)

        val toolbar = findViewById<Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        val ab = supportActionBar
        ab?.setDisplayHomeAsUpEnabled(true)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId


        if (id == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onBackStackChanged() {
        (supportFragmentManager.findFragmentByTag("Settings") as? SettingsFragment)?.let {
            mSettingsFragment = it
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
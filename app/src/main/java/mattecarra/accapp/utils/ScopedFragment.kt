package mattecarra.accapp.utils

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

abstract class ScopedFragment: Fragment(), CoroutineScope {
    protected lateinit var job: Job

    // Swallow any exception escaping a launch{} so a single coroutine failure
    // never crashes the whole process. Just log it.
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogExt().e(this.javaClass.simpleName, "Uncaught coroutine exception: $throwable")
    }

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main + coroutineExceptionHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
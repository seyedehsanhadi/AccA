package mattecarra.accapp.widget

import android.app.PendingIntent
import android.os.Build

/**
 * Android 12 requires every PendingIntent to declare its mutability. The widget's did not, so
 * getBroadcast() threw IllegalArgumentException on every update and the catch around the render
 * swallowed it: the widget sat on its placeholder icon forever, with no visible error. Nothing
 * here needs a mutable intent -- these are self-addressed broadcasts with their extras already
 * filled in -- so FLAG_IMMUTABLE is the correct answer everywhere in this package.
 */
internal fun pendingFlags(base: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) base or PendingIntent.FLAG_IMMUTABLE else base

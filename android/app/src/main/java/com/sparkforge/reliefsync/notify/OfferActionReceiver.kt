package com.sparkforge.reliefsync.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sparkforge.reliefsync.data.Api
import com.sparkforge.reliefsync.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles ACCEPT / DECLINE straight from the lock-screen notification. */
class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val vid = intent.getStringExtra("volunteer") ?: return
        val accept = intent.getBooleanExtra("accept", false)
        Notifier.cancelOffer(ctx)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching { Api(Prefs(ctx)).respond(vid, accept) }.isSuccess
            launch(Dispatchers.Main) {
                Toast.makeText(ctx, if (!ok) "Offline – reply YES by SMS" else if (accept) "Accepted – route on its way" else "Passed to next volunteer", Toast.LENGTH_SHORT).show()
                pending.finish()
            }
        }
    }
}

package com.osmaicoach.collector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object OsmLauncher {
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(OSM_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            Toast.makeText(context, "OSM não encontrado. Abrindo a Play Store.", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$OSM_PACKAGE")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

package com.isene.onepage

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import uniffi.fe2o3_mobile_core.Target
import uniffi.fe2o3_mobile_core.panelAdd
import uniffi.fe2o3_mobile_core.panelHas

/**
 * Takes what an app hands to the home screen with "Add to Home screen": a
 * contact, a chat, a web page. Android asks the home app to say yes, and
 * OnePage puts it last in the app panel. It shows nothing but one line.
 */
class PinActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = getSystemService(LauncherApps::class.java)
        val request = try {
            launcher.getPinItemRequest(intent)
        } catch (_: Exception) {
            null
        }
        val info = request
            ?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }
            ?.shortcutInfo
        if (request != null && info != null) {
            val target = Target.Shortcut(info.`package`, info.id)
            val panel = PanelStore.get(this)
            val had = panelHas(panel, target)
            if (request.accept()) {
                if (!had) PanelStore.set(this, panelAdd(panel, target))
                val what = info.shortLabel ?: "It"
                Toast.makeText(
                    this, if (had) "$what is already in the panel" else "$what is in the panel now",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        finish()
    }
}

package com.smartgallery.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * DISABLED.
 *
 * The old version COPIED a shared image into Pictures/SmartGallery/.
 * Smart Gallery now manages the ORIGINAL WhatsApp image only and never creates
 * copies. A shared image is not a MediaStore original, so it cannot be managed.
 *
 * The class is kept (not in the manifest any more) so nothing else breaks.
 * You may delete this file if you wish.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Toast.makeText(
            this,
            "Smart Gallery manages WhatsApp images automatically. Sharing is not needed.",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
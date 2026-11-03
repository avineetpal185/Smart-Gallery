package com.smartgallery.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartgallery.app.ui.theme.warningColor
import com.smartgallery.app.ui.theme.warningContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Works in the background while the app is open:
 *  - marks newly expired images as pending,
 *  - deletes them automatically if "All files access" is allowed.
 *
 * It shows a card ONLY in the rare case that some expired images could not be
 * deleted automatically (then Android's confirm screen is needed).
 *
 * The setup options (Allow buttons) now live in the gear icon: SetupCenter.kt.
 */
@Composable
fun PendingDeletionBanner() {

    val context = LocalContext.current

    var pending by remember {
        mutableStateOf(0)
    }

    LaunchedEffect(Unit) {

        while (true) {

            pending =
                withContext(Dispatchers.IO) {
                    try {
                        SmartGalleryStorage.markExpiredAsPending(context)
                        SmartGalleryStorage.deletePendingSilently(context)
                        SmartGalleryStorage.pendingCount(context)
                    } catch (exception: Exception) {
                        0
                    }
                }

            delay(5000)
        }
    }

    if (pending > 0) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = warningContainerColor()
            )
        ) {

            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = "🗑️",
                    fontSize = 24.sp
                )

                Spacer(
                    modifier = Modifier.width(10.dp)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {

                    Text(
                        text = "$pending expired image(s) ready to delete",
                        style = MaterialTheme.typography.titleMedium,
                        color = warningColor()
                    )

                    Text(
                        text = "Android will ask you to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Button(
                    onClick = {
                        (context as? MainActivity)?.requestExpiredDeletion()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete now")
                }
            }
        }
    }
}
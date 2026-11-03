package com.smartgallery.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartgallery.app.ui.theme.successColor
import com.smartgallery.app.ui.theme.warningColor
import com.smartgallery.app.ui.theme.warningContainerColor
import kotlinx.coroutines.delay

/**
 * A round settings icon (gear). Tap it to open "Setup & permissions" with every
 * Allow option in one place. A small red dot shows when something still needs
 * to be allowed.
 *
 * Put it in the header of the Groups screen.
 */
@Composable
fun SetupCenterButton() {

    val context = LocalContext.current

    var open by remember { mutableStateOf(false) }

    var notificationOn by remember { mutableStateOf(true) }
    var importOn by remember { mutableStateOf(true) }
    var autoDeleteOn by remember { mutableStateOf(true) }

    // Re-check often so the status updates as soon as the user comes back
    // from Android's settings screen.
    LaunchedEffect(Unit) {

        while (true) {

            notificationOn = isNotificationListenerEnabled(context)
            importOn = isImportServiceEnabled(context)

            autoDeleteOn =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        SmartGalleryStorage.hasAllFilesAccess()

            delay(1500)
        }
    }

    val needsAttention = !notificationOn || !importOn || !autoDeleteOn

    // ---------------- THE ICON ----------------

    Box(
        modifier = Modifier.size(52.dp),
        contentAlignment = Alignment.Center
    ) {

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚙️",
                fontSize = 20.sp
            )
        }

        if (needsAttention) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935))
            )
        }
    }

    // ---------------- THE POPUP ----------------

    if (open) {

        val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 3 else 2

        val enabledCount =
            (if (notificationOn) 1 else 0) +
                    (if (importOn) 1 else 0) +
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && autoDeleteOn) 1 else 0)

        AlertDialog(
            onDismissRequest = { open = false },

            shape = RoundedCornerShape(28.dp),

            title = {
                Column {
                    Text(
                        text = "Setup & permissions",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text =
                        if (enabledCount >= total)
                            "Everything is set up ✓"
                        else
                            "$enabledCount of $total enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                        if (enabledCount >= total)
                            successColor()
                        else
                            warningColor()
                    )
                }
            },

            text = {

                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    SetupRow(
                        icon = "🔔",
                        title = "Notification access",
                        description = "Lets Smart Gallery know which WhatsApp group an image came from.",
                        enabled = notificationOn,
                        buttonText = "Allow",
                        onClick = {
                            openSettings(
                                context,
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    )

                    SetupRow(
                        icon = "👥",
                        title = "Load all groups",
                        description = "Turn on, then open WhatsApp > Groups tab and scroll to the bottom.",
                        enabled = importOn,
                        buttonText = "Turn on",
                        onClick = {
                            openSettings(
                                context,
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                        SetupRow(
                            icon = "🗑️",
                            title = "Automatic deletion",
                            description = "Allow \"All files access\" once, and expired WhatsApp images are deleted automatically without asking each time.",
                            enabled = autoDeleteOn,
                            buttonText = "Allow",
                            onClick = { openAllFilesAccess(context) }
                        )
                    }
                }
            },

            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("Done")
                }
            }
        )
    }
}

// ================================================================
// ONE ROW IN THE POPUP
// ================================================================

@Composable
private fun SetupRow(
    icon: String,
    title: String,
    description: String,
    enabled: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {

    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (enabled) scheme.surfaceVariant.copy(alpha = 0.6f)
                else warningContainerColor()
            )
            .padding(12.dp)
    ) {

        Column {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = icon,
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface
                    )

                    Text(
                        text = if (enabled) "✓ ON" else "Needs permission",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) successColor() else warningColor()
                    )
                }
            }

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(10.dp))

            if (enabled) {

                // ON: a way to turn it OFF (opens the same Android settings screen).
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp
                    )
                ) {
                    Text("Turn off")
                }

            } else {

                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
                ) {
                    Text(buttonText)
                }
            }
        }
    }
}

// ================================================================
// OPEN ANDROID SETTINGS SCREENS
// ================================================================

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (exception: Exception) {
        // Some phones do not have this screen: ignore.
    }
}

private fun openAllFilesAccess(context: Context) {

    try {

        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )

    } catch (exception: Exception) {

        try {

            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )

        } catch (second: Exception) {

            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Exception) {
            }
        }
    }
}
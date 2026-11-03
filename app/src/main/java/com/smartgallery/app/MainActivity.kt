package com.smartgallery.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartgallery.app.ui.theme.BrandGradientEnd
import com.smartgallery.app.ui.theme.BrandGradientStart
import com.smartgallery.app.ui.theme.SmartGallaryTheme
import kotlinx.coroutines.delay

private const val MAX_SELECTED_GROUPS = 10

data class WhatsAppGroup(
    val id: Int,
    val name: String,
    val whatsappId: String,
    val selected: Boolean = false,
    val retentionDays: Int = 7
)

class MainActivity : ComponentActivity() {

    // Deletes expired ORIGINAL WhatsApp images.
    private lateinit var expiredDeleter: ExpiredImageDeleter

    private var askedAboutExpiredThisLaunch = false

    // Replaces onRequestPermissionsResult (no override, no red error).
    private val mediaPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            // Permission just granted: start detection so the
            // "ignore old images" baseline is taken right now.
            if (granted) {
                startWhatsAppMediaDetection()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be created here, before the screen starts.
        expiredDeleter = ExpiredImageDeleter(this)

        requestGalleryPermission()

        // Periodic expiry check.
        RetentionScheduler.start(this)

        // Watch for NEW WhatsApp images (safe to call repeatedly).
        startWhatsAppMediaDetection()

        // If the phone dropped the notification listener, ask it to reconnect.
        try {
            if (isNotificationListenerEnabled(this)) {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(
                        this,
                        GroupNotificationListener::class.java
                    )
                )
            }
        } catch (_: Exception) {
        }

        setContent {
            SmartGallaryTheme {
                SmartGalleryApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Wait until the photo permission has been answered first.
        if (!hasGalleryPermission()) return

        // FIRST-TIME SETUP: open Android's "All files access" screen ONCE,
        // right after the photo permission. After the user turns it on,
        // expired images are deleted automatically with no confirmation.
        askAllFilesAccessOnce()

        // Once per app launch: delete images that expired while the app was closed.
        // Android 11+ without the permission: do NOT pop up the confirm screen
        // by itself (the banner button is used instead).
        if (!askedAboutExpiredThisLaunch) {
            askedAboutExpiredThisLaunch = true

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                SmartGalleryStorage.hasAllFilesAccess()
            ) {
                expiredDeleter.start()
            }
        }
    }

    /** Called by the "Delete now" button. */
    fun requestExpiredDeletion() {
        expiredDeleter.start()
    }

    private fun askAllFilesAccessOnce() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        if (SmartGalleryStorage.hasAllFilesAccess()) return

        val setup = getSharedPreferences("smart_gallery_setup", Context.MODE_PRIVATE)

        if (setup.getBoolean("all_files_access_asked_v1", false)) return

        setup.edit().putBoolean("all_files_access_asked_v1", true).apply()

        Toast.makeText(
            this,
            "One-time setup: turn ON \"Allow access to manage all files\" for Smart Gallery, then come back.",
            Toast.LENGTH_LONG
        ).show()

        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (exception: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    private fun hasGalleryPermission(): Boolean {

        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startWhatsAppMediaDetection() {
        WhatsAppMediaWorker.start(applicationContext)
    }

    private fun requestGalleryPermission() {

        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        if (
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            mediaPermissionLauncher.launch(permission)
        }
    }
}

@Composable
fun SmartGalleryApp() {

    val context = LocalContext.current

    var currentScreen by remember {
        mutableStateOf("groups")
    }

    val preferences = remember {
        context.getSharedPreferences(
            "smart_gallery_settings",
            Context.MODE_PRIVATE
        )
    }

    val groups = remember {
        mutableStateListOf<WhatsAppGroup>()
    }

    var searchText by remember {
        mutableStateOf("")
    }

    var loading by remember {
        mutableStateOf(true)
    }

    var errorMessage by remember {
        mutableStateOf("")
    }

    var showAddGroupDialog by remember {
        mutableStateOf(false)
    }

    var newGroupName by remember {
        mutableStateOf("")
    }

    var pendingImport by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    /*
     * LOAD GROUPS FROM PHONE ONLY
     */

    LaunchedEffect(Unit) {

        groups.clear()

        loadLocalGroupNames(preferences).forEachIndexed { index, name ->

            val selected =
                preferences.getBoolean(
                    "selected_$name",
                    false
                )

            val retention =
                preferences.getInt(
                    "retention_$name",
                    7
                )

            groups.add(
                WhatsAppGroup(
                    id = index + 1,
                    name = name,
                    whatsappId = "",
                    selected = selected,
                    retentionDays = retention
                )
            )
        }

        loading = false
        errorMessage = ""
    }

    /*
     * AUTO-DISCOVERY
     *
     * Every few seconds:
     *  - check for groups found by the import service
     *  - add any group the notification listener has discovered
     */

    LaunchedEffect(Unit) {

        while (true) {

            pendingImport = readPendingImport(context)

            loadLocalGroupNames(preferences).forEach { name ->

                if (groups.none { it.name.equals(name, ignoreCase = true) }) {

                    groups.add(
                        WhatsAppGroup(
                            id = (groups.maxOfOrNull { it.id } ?: 0) + 1,
                            name = name,
                            whatsappId = "",
                            selected = preferences.getBoolean(
                                "selected_$name",
                                false
                            ),
                            retentionDays = preferences.getInt(
                                "retention_$name",
                                7
                            )
                        )
                    )
                }
            }

            delay(3000)
        }
    }

    val selectedCount =
        groups.count { it.selected }

    // Display order only: protected groups first, then A-Z.
    val filteredGroups =
        groups
            .filter {
                it.name.contains(
                    searchText,
                    ignoreCase = true
                )
            }
            .sortedWith(
                compareByDescending<WhatsAppGroup> { it.selected }
                    .thenBy { it.name.lowercase() }
            )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        /*
         * EXPIRED-IMAGE WORKER
         * Marks / deletes expired images while the app is open.
         * Shows a card only if Android's confirm screen is still needed.
         * (The Allow options are in the gear icon: SetupCenter.kt)
         */

        PendingDeletionBanner()

        if (pendingImport.isNotEmpty()) {

            AlertDialog(
                onDismissRequest = { },
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Groups found in WhatsApp",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "${pendingImport.size} groups were found. Scroll WhatsApp's Groups tab to the bottom first to catch them all, then add them."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingImport.forEach { name ->
                                if (
                                    groups.none {
                                        it.name.equals(name, ignoreCase = true)
                                    }
                                ) {
                                    groups.add(
                                        WhatsAppGroup(
                                            id = (groups.maxOfOrNull { it.id } ?: 0) + 1,
                                            name = name,
                                            whatsappId = "",
                                            selected = false,
                                            retentionDays = 7
                                        )
                                    )
                                }
                            }

                            saveLocalGroupNames(
                                preferences,
                                groups.map { it.name }
                            )

                            clearPendingImport(context)
                            pendingImport = emptyList()
                        }
                    ) {
                        Text("Add all")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            clearPendingImport(context)
                            pendingImport = emptyList()
                        }
                    ) {
                        Text("Dismiss")
                    }
                }
            )
        }

        /*
         * MAIN CONTENT
         */

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {

            if (currentScreen == "groups") {

                GroupsScreen(
                    groups = groups,
                    filteredGroups = filteredGroups,
                    selectedCount = selectedCount,
                    searchText = searchText,
                    loading = loading,
                    errorMessage = errorMessage,

                    onSearchChanged = {
                        searchText = it
                    },

                    onSelectionChanged = { group, selected ->

                        if (
                            selected &&
                            !group.selected &&
                            selectedCount >=
                            MAX_SELECTED_GROUPS
                        ) {

                            Toast.makeText(
                                context,
                                "Maximum 10 groups allowed",
                                Toast.LENGTH_SHORT
                            ).show()

                            return@GroupsScreen
                        }

                        val index =
                            groups.indexOfFirst {
                                it.id == group.id
                            }

                        if (index != -1) {

                            groups[index] =
                                group.copy(
                                    selected = selected
                                )

                            preferences.edit()
                                .putBoolean(
                                    "selected_${group.name}",
                                    selected
                                )
                                .apply()

                            saveLocalGroupNames(
                                preferences,
                                groups.map { it.name }
                            )
                        }
                    },

                    onRetentionChanged = {
                            group,
                            retentionDays ->

                        val index =
                            groups.indexOfFirst {
                                it.id == group.id
                            }

                        if (index != -1) {

                            groups[index] =
                                group.copy(
                                    retentionDays =
                                    retentionDays
                                )

                            preferences.edit()
                                .putInt(
                                    "retention_${group.name}",
                                    retentionDays
                                )
                                .apply()

                            saveLocalGroupNames(
                                preferences,
                                groups.map { it.name }
                            )
                        }
                    },

                    onAddGroup = {
                        newGroupName = ""
                        showAddGroupDialog = true
                    }
                )

            } else {

                GalleryScreen(
                    onBack = {
                        currentScreen = "groups"
                    }
                )
            }
        }

        if (showAddGroupDialog) {

            AlertDialog(
                onDismissRequest = {
                    showAddGroupDialog = false
                },
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Add WhatsApp Groups",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("Group names (one per line)") },
                        singleLine = false,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val names =
                                newGroupName
                                    .split("\n", ",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .distinctBy { it.lowercase() }

                            if (names.isEmpty()) {

                                Toast.makeText(
                                    context,
                                    "Enter a group name",
                                    Toast.LENGTH_SHORT
                                ).show()

                            } else {

                                var added = 0

                                names.forEach { name ->

                                    if (
                                        groups.none {
                                            it.name.equals(
                                                name,
                                                ignoreCase = true
                                            )
                                        }
                                    ) {

                                        groups.add(
                                            WhatsAppGroup(
                                                id = (groups.maxOfOrNull { it.id } ?: 0) + 1,
                                                name = name,
                                                whatsappId = "",
                                                selected = false,
                                                retentionDays = 7
                                            )
                                        )

                                        added++
                                    }
                                }

                                saveLocalGroupNames(
                                    preferences,
                                    groups.map { it.name }
                                )

                                Toast.makeText(
                                    context,
                                    if (added == 0)
                                        "Already in the list"
                                    else
                                        "$added group(s) added",
                                    Toast.LENGTH_SHORT
                                ).show()

                                showAddGroupDialog = false
                                newGroupName = ""
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddGroupDialog = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        /*
         * BOTTOM NAVIGATION
         */

        NavigationBar {

            NavigationBarItem(
                selected =
                currentScreen == "groups",

                onClick = {
                    currentScreen = "groups"
                },

                icon = {
                    Text("👥", fontSize = 20.sp)
                },

                label = {
                    Text("Groups")
                }
            )

            NavigationBarItem(
                selected =
                currentScreen == "gallery",

                onClick = {
                    currentScreen = "gallery"
                },

                icon = {
                    Text("🖼️", fontSize = 20.sp)
                },

                label = {
                    Text("My Galleries")
                }
            )
        }
    }
}

/*
 * GROUPS SCREEN
 */

@Composable
fun GroupsScreen(
    groups: List<WhatsAppGroup>,
    filteredGroups: List<WhatsAppGroup>,
    selectedCount: Int,
    searchText: String,
    loading: Boolean,
    errorMessage: String,
    onSearchChanged: (String) -> Unit,
    onSelectionChanged:
        (WhatsAppGroup, Boolean) -> Unit,
    onRetentionChanged:
        (WhatsAppGroup, Int) -> Unit,
    onAddGroup: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        /*
         * HEADER
         */

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
            Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        MaterialTheme.colorScheme
                            .primaryContainer
                    ),

                contentAlignment =
                Alignment.Center
            ) {

                Image(
                    painter = painterResource(
                        id = R.drawable.smart_gallery_logo
                    ),
                    contentDescription = "Smart Gallery Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column {

                Text(
                    text = "Smart Gallery",
                    style =
                    MaterialTheme.typography
                        .headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text =
                    "WhatsApp media manager",
                    style =
                    MaterialTheme.typography
                        .bodySmall,
                    color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            SetupCenterButton()
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        /*
         * SELECTED SUMMARY
         */

        ProtectedSummaryCard(selectedCount = selectedCount)

        Spacer(
            modifier = Modifier.height(14.dp)
        )

        /*
         * SEARCH
         */

        OutlinedTextField(
            modifier =
            Modifier.fillMaxWidth(),

            value = searchText,

            onValueChange =
            onSearchChanged,

            placeholder = {
                Text(
                    "Search by group name"
                )
            },

            leadingIcon = {
                Text("🔍", fontSize = 16.sp)
            },

            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    TextButton(onClick = { onSearchChanged("") }) {
                        Text("Clear")
                    }
                }
            },

            singleLine = true,

            shape =
            RoundedCornerShape(16.dp)
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Text(
                text = "WhatsApp Groups",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${filteredGroups.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        /*
         * LOADING
         */

        if (loading) {

            Box(
                modifier =
                Modifier.fillMaxSize(),
                contentAlignment =
                Alignment.Center
            ) {

                CircularProgressIndicator()
            }

        }

        /*
         * ERROR
         */

        else if (errorMessage.isNotEmpty()) {

            Box(
                modifier =
                Modifier.fillMaxSize(),
                contentAlignment =
                Alignment.Center
            ) {

                Column(
                    horizontalAlignment =
                    Alignment.CenterHorizontally
                ) {

                    Text("⚠️", fontSize = 36.sp)

                    Spacer(
                        modifier =
                        Modifier.height(8.dp)
                    )

                    Text(
                        text =
                        "Connection failed",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text =
                        errorMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }

        /*
         * EMPTY LIST
         */

        else if (filteredGroups.isEmpty()) {

            Box(
                modifier =
                Modifier.fillMaxSize(),
                contentAlignment =
                Alignment.Center
            ) {

                Column(
                    horizontalAlignment =
                    Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {

                    Text("💬", fontSize = 40.sp)

                    Spacer(
                        modifier =
                        Modifier.height(8.dp)
                    )

                    Text(
                        text =
                        if (searchText.isNotEmpty())
                            "No groups match your search"
                        else
                            "No groups found yet",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (searchText.isEmpty()) {
                        Text(
                            text = "Turn on Notification access in the gear icon. Groups appear here when WhatsApp sends a message.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        }

        /*
         * GROUP LIST
         */

        else {

            LazyColumn(
                modifier =
                Modifier.fillMaxSize(),

                contentPadding = PaddingValues(bottom = 16.dp),

                verticalArrangement =
                Arrangement.spacedBy(10.dp)
            ) {

                items(
                    items = filteredGroups,
                    key = {
                        it.id
                    }
                ) { group ->

                    GroupManagementCard(
                        group = group,

                        onSelectionChanged = {
                                selected ->

                            onSelectionChanged(
                                group,
                                selected
                            )
                        },

                        onRetentionChanged = {
                                retentionDays ->

                            onRetentionChanged(
                                group,
                                retentionDays
                            )
                        }
                    )
                }
            }
        }
    }
}

/*
 * SUMMARY CARD ("Groups protected")
 */

@Composable
private fun ProtectedSummaryCard(selectedCount: Int) {

    val fraction =
        (selectedCount.toFloat() / MAX_SELECTED_GROUPS.toFloat())
            .coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(BrandGradientStart, BrandGradientEnd)
                )
            )
            .padding(20.dp)
    ) {

        Column {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        text = "Groups protected",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "$selectedCount / $MAX_SELECTED_GROUPS selected",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "$selectedCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 40.sp,
                        lineHeight = 44.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Progress bar (drawn by hand, so it works on any Material version).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {

                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

/*
 * GROUP CARD
 */

private val AvatarPalette = listOf(
    Color(0xFF5B3EC8),
    Color(0xFF00897B),
    Color(0xFFEF8A17),
    Color(0xFFD81B60),
    Color(0xFF1E88E5),
    Color(0xFF6D4C41),
    Color(0xFF43A047),
    Color(0xFF8E24AA)
)

private fun avatarColor(name: String): Color {
    val index = (name.lowercase().hashCode() and 0x7fffffff) % AvatarPalette.size
    return AvatarPalette[index]
}

@Composable
fun GroupManagementCard(
    group: WhatsAppGroup,
    onSelectionChanged: (Boolean) -> Unit,
    onRetentionChanged: (Int) -> Unit
) {

    val scheme = MaterialTheme.colorScheme

    val initials =
        group.name
            .trim()
            .split(" ")
            .filter {
                it.isNotEmpty()
            }
            .take(2)
            .joinToString("") {
                it.first().uppercase()
            }
            .ifEmpty { "?" }

    val accent = avatarColor(group.name)

    Card(
        modifier =
        Modifier.fillMaxWidth(),

        shape =
        RoundedCornerShape(20.dp),

        colors =
        CardDefaults.cardColors(
            containerColor =
            if (group.selected)
                scheme.primaryContainer
            else
                scheme.surface
        ),

        border =
        BorderStroke(
            if (group.selected) 1.5.dp else 1.dp,
            if (group.selected) scheme.primary else scheme.outlineVariant
        ),

        elevation =
        CardDefaults.cardElevation(
            defaultElevation = if (group.selected) 0.dp else 1.dp
        )
    ) {

        Column(
            modifier =
            Modifier.padding(14.dp)
        ) {

            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectionChanged(!group.selected) },
                verticalAlignment =
                Alignment.CenterVertically
            ) {

                Box(
                    modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (group.selected)
                                scheme.primary
                            else
                                accent.copy(alpha = 0.16f)
                        ),

                    contentAlignment =
                    Alignment.Center
                ) {

                    Text(
                        text = initials,
                        color =
                        if (group.selected)
                            scheme.onPrimary
                        else
                            accent,
                        fontWeight =
                        FontWeight.Bold
                    )
                }

                Spacer(
                    modifier =
                    Modifier.width(12.dp)
                )

                Column(
                    modifier =
                    Modifier.weight(1f)
                ) {

                    Text(
                        text =
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text =
                        if (group.selected)
                            "✓ Smart Gallery active"
                        else
                            "Not managed",
                        style =
                        MaterialTheme.typography
                            .bodySmall,
                        color =
                        if (group.selected)
                            scheme.primary
                        else
                            scheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked =
                    group.selected,

                    onCheckedChange =
                    onSelectionChanged
                )
            }

            AnimatedVisibility(visible = group.selected) {

                Column {

                    Spacer(
                        modifier =
                        Modifier.height(12.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(scheme.surface.copy(alpha = 0.75f))
                            .padding(12.dp)
                    ) {

                        Column {

                            Row(
                                modifier =
                                Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Text(
                                    text =
                                    "⏱ Auto-delete after",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurface
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(scheme.primary)
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text =
                                        if (group.retentionDays == 5)
                                            "5 minutes"
                                        else
                                            "${group.retentionDays} days",
                                        color = scheme.onPrimary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            Spacer(
                                modifier =
                                Modifier.height(10.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {

                                RetentionButton(
                                    modifier = Modifier.weight(1f),
                                    days = 5,
                                    selected = group.retentionDays == 5,
                                    onClick = onRetentionChanged
                                )

                                RetentionButton(
                                    modifier = Modifier.weight(1f),
                                    days = 1,
                                    selected = group.retentionDays == 1,
                                    onClick = onRetentionChanged
                                )

                                RetentionButton(
                                    modifier = Modifier.weight(1f),
                                    days = 7,
                                    selected = group.retentionDays == 7,
                                    onClick = onRetentionChanged
                                )

                                RetentionButton(
                                    modifier = Modifier.weight(1f),
                                    days = 15,
                                    selected = group.retentionDays == 15,
                                    onClick = onRetentionChanged
                                )

                                RetentionButton(
                                    modifier = Modifier.weight(1f),
                                    days = 30,
                                    selected = group.retentionDays == 30,
                                    onClick = onRetentionChanged
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/*
 * RETENTION BUTTON
 */

@Composable
fun RetentionButton(
    modifier: Modifier = Modifier,
    days: Int,
    selected: Boolean,
    onClick: (Int) -> Unit
) {

    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) scheme.primary else scheme.surfaceVariant
            )
            .clickable { onClick(days) },
        contentAlignment = Alignment.Center
    ) {

        Text(
            text =
            if (days == 5)
                "5m"
            else
                "${days}d",
            color =
            if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            fontWeight =
            if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

/*
 * LOCAL GROUP STORAGE
 * All group names and settings stay on the phone.
 *
 * NOTE: GroupNotificationListener.kt uses the same key
 * ("local_group_names") to add groups it discovers.
 */

private const val LOCAL_GROUPS_KEY = "local_group_names"

fun loadLocalGroupNames(
    preferences: android.content.SharedPreferences
): List<String> {

    val names =
        preferences.getStringSet(
            LOCAL_GROUPS_KEY,
            emptySet()
        )?.toMutableSet()
            ?: mutableSetOf()

    // Preserve groups that were already selected before the backend was removed.
    preferences.all.keys.forEach { key ->
        if (key.startsWith("selected_")) {
            val name = key.removePrefix("selected_")
            if (name.isNotBlank()) {
                names.add(name)
            }
        }
    }

    val result = names
        .filter { it.isNotBlank() }
        .sorted()

    preferences.edit()
        .putStringSet(LOCAL_GROUPS_KEY, result.toSet())
        .apply()

    return result
}

/*
 * MERGES with the saved names instead of overwriting them, so groups
 * discovered in the background by the notification listener are never lost.
 */
fun saveLocalGroupNames(
    preferences: android.content.SharedPreferences,
    names: List<String>
) {

    val merged =
        (preferences.getStringSet(
            LOCAL_GROUPS_KEY,
            emptySet()
        ) ?: emptySet()).toMutableSet()

    merged.addAll(names.filter { it.isNotBlank() })

    preferences.edit()
        .putStringSet(LOCAL_GROUPS_KEY, merged)
        .apply()
}
package com.smartgallery.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val GALLERY_REFRESH_MS = 10_000L

/** Same group, ignoring case and stray spaces (folder names can end with a space). */
private fun sameGroup(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return a.trim().equals(b.trim(), ignoreCase = true)
}

@Composable
fun GalleryScreen(
    onBack: () -> Unit
) {

    val context =
        LocalContext.current

    var images by remember {
        mutableStateOf<List<GalleryImage>>(
            emptyList()
        )
    }

    var selectedGroups by remember {
        mutableStateOf<List<String>>(
            emptyList()
        )
    }

    var selectedGroup by remember {
        mutableStateOf<String?>(null)
    }

    var loaded by remember {
        mutableStateOf(false)
    }

    var viewerIndex by remember {
        mutableStateOf<Int?>(null)
    }

    // ============================================================
    // AUTOMATIC SYNC + GALLERY REFRESH
    // ============================================================

    LaunchedEffect(Unit) {

        while (true) {

            // Reload selected groups
            val newGroups =
                withContext(Dispatchers.IO) {

                    readSelectedGroups(
                        context
                    )
                }

            // Reload images from phone
            val newImages =
                withContext(Dispatchers.IO) {

                    readSmartGalleryImages(
                        context
                    )
                }

            selectedGroups =
                newGroups

            images =
                newImages

            loaded = true

            // Wait 10 seconds
            delay(
                GALLERY_REFRESH_MS
            )
        }
    }

    val currentGroup = selectedGroup

    val groupImages =
        remember(images, currentGroup) {
            if (currentGroup == null) {
                emptyList()
            } else {
                images.filter { sameGroup(it.groupName, currentGroup) }
            }
        }

    // System back: close viewer first, then leave the folder.
    BackHandler(enabled = viewerIndex != null || currentGroup != null) {
        if (viewerIndex != null) {
            viewerIndex = null
        } else {
            selectedGroup = null
        }
    }

    // ============================================================
    // MAIN SCREEN
    // ============================================================

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        // ========================================================
        // HEADER
        // ========================================================

        Row(
            modifier =
            Modifier.fillMaxWidth(),

            verticalAlignment =
            Alignment.CenterVertically
        ) {

            RoundIconButton(
                label = "←",
                onClick = {
                    if (currentGroup != null) {
                        selectedGroup = null
                    } else {
                        onBack()
                    }
                }
            )

            Spacer(
                modifier =
                Modifier.width(12.dp)
            )

            Column {

                Text(
                    text = currentGroup ?: "My Galleries",
                    style =
                    MaterialTheme
                        .typography
                        .titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text =
                    if (currentGroup == null)
                        "Your Smart Galleries"
                    else
                        "${groupImages.size} image" +
                                if (groupImages.size != 1) "s" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(
            modifier =
            Modifier.height(16.dp)
        )

        if (!loaded) {

            // ----------------------------------------------------
            // FIRST LOAD
            // ----------------------------------------------------

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

        } else if (currentGroup == null) {

            // ====================================================
            // GALLERY LIST
            // ====================================================

            if (selectedGroups.isEmpty()) {

                EmptyState(
                    icon = "📂",
                    title = "No groups selected",
                    message = "Select WhatsApp groups first."
                )

            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement =
                    Arrangement.spacedBy(10.dp)
                ) {

                    items(
                        items = selectedGroups,
                        key = { it }
                    ) { group ->

                        val groupList =
                            images.filter { sameGroup(it.groupName, group) }

                        FolderCard(
                            name = group,
                            count = groupList.size,
                            cover = groupList.firstOrNull(),
                            onClick = { selectedGroup = group }
                        )
                    }
                }
            }

        } else {

            // ====================================================
            // INSIDE ONE GALLERY
            // ====================================================

            if (groupImages.isEmpty()) {

                EmptyState(
                    icon = "📷",
                    title = "No images yet",
                    message = "New WhatsApp images will appear here."
                )

            } else {

                // ------------------------------------------------
                // IMAGE GRID
                // ------------------------------------------------

                LazyVerticalGrid(
                    columns =
                    GridCells.Fixed(3),

                    modifier =
                    Modifier.fillMaxSize(),

                    contentPadding =
                    PaddingValues(
                        bottom = 20.dp
                    ),

                    horizontalArrangement =
                    Arrangement.spacedBy(6.dp),

                    verticalArrangement =
                    Arrangement.spacedBy(6.dp)
                ) {

                    items(
                        items = groupImages,

                        key = {
                            it.uri.toString()
                        }

                    ) { image ->

                        GalleryImageItem(
                            image = image,
                            onClick = {
                                viewerIndex = groupImages.indexOf(image)
                            }
                        )
                    }
                }
            }
        }
    }

    // ============================================================
    // FULL-SCREEN VIEWER
    // ============================================================

    val index = viewerIndex

    if (index != null && index in groupImages.indices) {

        ImageViewer(
            images = groupImages,
            startIndex = index,
            onIndexChange = { viewerIndex = it },
            onClose = { viewerIndex = null }
        )

    } else if (index != null) {

        // The image expired / was removed while open.
        viewerIndex = null
    }
}

// ================================================================
// SMALL PIECES
// ================================================================

@Composable
private fun RoundIconButton(
    label: String,
    onClick: () -> Unit
) {

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyState(
    icon: String,
    title: String,
    message: String
) {

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

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 40.sp)
            }

            Spacer(
                modifier =
                Modifier.height(16.dp)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(
                modifier =
                Modifier.height(4.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderCard(
    name: String,
    count: Int,
    cover: GalleryImage?,
    onClick: () -> Unit
) {

    val scheme = MaterialTheme.colorScheme

    Card(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {

        Row(
            modifier =
            Modifier.padding(12.dp),

            verticalAlignment =
            Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {

                if (cover != null) {
                    SmartImage(
                        uri = cover.uri,
                        description = cover.name,
                        targetPx = 200,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "📁", fontSize = 28.sp)
                }
            }

            Spacer(
                modifier =
                Modifier.width(14.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = name,
                    style =
                    MaterialTheme
                        .typography
                        .titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text =
                    "$count image" +
                            if (count != 1)
                                "s"
                            else
                                "",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            Text(
                text = "›",
                fontSize = 28.sp,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

// ================================================================
// IMAGE ITEM (grid cell)
// ================================================================

@Composable
private fun GalleryImageItem(
    image: GalleryImage,
    onClick: () -> Unit
) {

    Card(
        modifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {

        SmartImage(
            uri = image.uri,
            description = image.name,
            targetPx = 360,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ================================================================
// IMAGE LOADING (down-sampled, so big photos do not use much memory)
// ================================================================

@Composable
private fun SmartImage(
    uri: Uri,
    description: String,
    targetPx: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {

    val context =
        LocalContext.current

    var bitmap by remember(
        uri
    ) {

        mutableStateOf<Bitmap?>(
            null
        )
    }

    LaunchedEffect(
        uri
    ) {

        bitmap =
            withContext(
                Dispatchers.IO
            ) {
                decodeSampled(context, uri, targetPx)
            }
    }

    val currentBitmap =
        bitmap

    if (currentBitmap != null) {

        Image(
            bitmap =
            currentBitmap
                .asImageBitmap(),

            contentDescription =
            description,

            modifier = modifier,

            contentScale =
            contentScale
        )

    } else {

        Box(
            modifier = modifier,

            contentAlignment =
            Alignment.Center
        ) {

            Text("📷")
        }
    }
}

private fun decodeSampled(
    context: Context,
    uri: Uri,
    targetPx: Int
): Bitmap? {

    return try {

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)

        while (maxSide / (sample * 2) >= targetPx) {
            sample *= 2
        }

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
            }

        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

    } catch (exception: Exception) {

        null

    } catch (error: OutOfMemoryError) {

        null
    }
}

// ================================================================
// FULL-SCREEN VIEWER
// ================================================================

@Composable
private fun ImageViewer(
    images: List<GalleryImage>,
    startIndex: Int,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit
) {

    val image = images[startIndex]

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {

            SmartImage(
                uri = image.uri,
                description = image.name,
                targetPx = 1600,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color.White, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = image.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${startIndex + 1} / ${images.size}",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Previous / next
            if (startIndex > 0) {
                ViewerArrow(
                    label = "‹",
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { onIndexChange(startIndex - 1) }
                )
            }

            if (startIndex < images.size - 1) {
                ViewerArrow(
                    label = "›",
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onClick = { onIndexChange(startIndex + 1) }
                )
            }
        }
    }
}

@Composable
private fun ViewerArrow(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit
) {

    Box(
        modifier = modifier
            .padding(8.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 26.sp)
    }
}
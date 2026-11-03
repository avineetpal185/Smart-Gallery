package com.smartgallery.app

import android.util.Log
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

fun readSelectedGroups(
    context: Context
): List<String> {

    val preferences =
        context.getSharedPreferences(
            "smart_gallery_settings",
            Context.MODE_PRIVATE
        )

    val result =
        mutableListOf<String>()

    for (entry in preferences.all) {

        val key =
            entry.key

        if (
            key.startsWith("selected_") &&
            entry.value == true
        ) {

            result.add(
                key.removePrefix("selected_")
            )
        }
    }

    return result.sorted()
}


/**
 * Shows the ORIGINAL WhatsApp images that Smart Gallery manages.
 * Nothing is read from Pictures/SmartGallery/ any more (no copies exist).
 * An image whose original was deleted is simply not returned.
 */
fun readSmartGalleryImages(
    context: Context
): List<GalleryImage> {

    val tracked =
        SmartGalleryStorage.trackedList(context)

    if (tracked.isEmpty()) {
        return emptyList()
    }

    val byId =
        tracked.associateBy { it.id }

    val result =
        mutableListOf<GalleryImage>()

    val projection =
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

    // Chunked so the query never gets too long.
    for (chunk in tracked.map { it.id }.chunked(400)) {

        val selection =
            "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"

        try {

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Images.Media._ID
                    )

                val nameColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Images.Media.DISPLAY_NAME
                    )

                while (cursor.moveToNext()) {

                    val id =
                        cursor.getLong(idColumn)

                    val item =
                        byId[id]
                            ?: continue

                    val name =
                        cursor.getString(nameColumn)
                            ?: item.name

                    val uri =
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                    result.add(
                        GalleryImage(
                            uri = uri,
                            name = name,
                            groupName = item.group
                        )
                    )
                }
            }

        } catch (exception: Exception) {

            Log.e(
                "GalleryQuery",
                "Could not load images: ${exception.message}"
            )
        }
    }

    // Newest first (MediaStore ids grow with time).
    val sorted =
        result.sortedByDescending {
            ContentUris.parseId(it.uri)
        }

    Log.d(
        "GalleryQuery",
        "TOTAL ORIGINAL IMAGES LOADED: ${sorted.size}"
    )

    return sorted
}
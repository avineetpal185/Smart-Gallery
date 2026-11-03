package com.smartgallery.app

import android.net.Uri

data class GalleryImage(
    val uri: Uri,
    val name: String,
    val groupName: String
)
package com.neilturner.aerialviews.models.videos

import android.net.Uri

data class AerialVideo(val uri: Uri, var location: String = "", var poi: Map<Int, String> = emptyMap())

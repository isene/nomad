package com.isene.pointer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/** Lets the picture loader read one frame of a video, for the thumbnails
 *  in the list. The loader is built the first time a picture is asked for,
 *  so this costs the start of the app nothing. */
class PointerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}

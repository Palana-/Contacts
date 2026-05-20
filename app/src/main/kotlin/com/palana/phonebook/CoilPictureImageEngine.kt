package com.palana.phonebook

import android.content.Context
import android.widget.ImageView
import coil.load
import com.luck.picture.lib.engine.ImageEngine

object CoilPictureImageEngine : ImageEngine {
    override fun loadImage(context: Context, url: String, imageView: ImageView) {
        imageView.load(url)
    }

    override fun loadImage(context: Context, imageView: ImageView, url: String, maxWidth: Int, maxHeight: Int) {
        imageView.load(url) {
            size(maxWidth, maxHeight)
        }
    }

    override fun loadAlbumCover(context: Context, url: String, imageView: ImageView) {
        imageView.load(url)
    }

    override fun loadGridImage(context: Context, url: String, imageView: ImageView) {
        imageView.load(url)
    }

    override fun pauseRequests(context: Context) = Unit

    override fun resumeRequests(context: Context) = Unit
}

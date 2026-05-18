package com.palana.phonebook

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.luck.picture.lib.engine.ImageEngine

object ContactImageEngine : ImageEngine {
    override fun loadImage(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).into(imageView)
    }

    override fun loadImage(context: Context, imageView: ImageView, url: String, maxWidth: Int, maxHeight: Int) {
        Glide.with(context).load(url).override(maxWidth, maxHeight).into(imageView)
    }

    override fun loadAlbumCover(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).override(180, 180).centerCrop().into(imageView)
    }

    override fun loadGridImage(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).override(220, 220).centerCrop().into(imageView)
    }

    override fun pauseRequests(context: Context) {
        Glide.with(context).pauseRequests()
    }

    override fun resumeRequests(context: Context) {
        Glide.with(context).resumeRequests()
    }
}

package com.example.screensavemax

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.service.dreams.DreamService
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.*

class GifDreamService : DreamService() {

    private val prefs by lazy {
        getSharedPreferences("screensavemax_prefs", Context.MODE_PRIVATE)
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var imageView: ImageView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()

        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked) {
            finish()
            return
        }

        val uriString = prefs.getString("selected_folder_uri", null)
        if (uriString == null) {
            lockNow()
            return
        }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        setContentView(imageView)

        playOneAndFinish(Uri.parse(uriString))
    }

    private fun lockNow() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
        finish()
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        }
    }

    private fun playOneAndFinish(folderUri: Uri) {
        serviceScope.launch {
            val fileUris = withContext(Dispatchers.IO) {
                loadMediaFromFolder(folderUri)
            }

            if (fileUris.isEmpty()) {
                lockNow()
                return@launch
            }

            val selectedUri = fileUris.random()

            val isGif = withContext(Dispatchers.IO) {
                isGifFile(selectedUri)
            }

            if (isGif) {
                displayGifThenFinish(selectedUri)
            } else {
                displayStaticImageThenFinish(selectedUri)
            }
        }
    }

    private fun displayStaticImageThenFinish(uri: Uri) {
        val imageLoader = ImageLoader(this)
        val request = ImageRequest.Builder(this)
            .data(uri)
            .target { drawable ->
                imageView.setImageDrawable(drawable)
                serviceScope.launch {
                    delay(3000)
                    lockNow()
                }
            }
            .build()
        imageLoader.enqueue(request)
    }

    private fun displayGifThenFinish(uri: Uri) {
        val imageLoader = ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }.build()

        val request = ImageRequest.Builder(this)
            .data(uri)
            .target { drawable ->
                imageView.setImageDrawable(drawable)
                if (drawable is Animatable) {
                    if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = 0 // Play once

                        val timeoutJob = serviceScope.launch { delay(50000); lockNow() }

                        drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                            override fun onAnimationEnd(dr: Drawable?) {
                                timeoutJob.cancel()
                                serviceScope.launch { lockNow() }
                            }
                        })
                    } else {
                        // Fallback for old phones: play for 5s then lock
                        serviceScope.launch { delay(5000); lockNow() }
                    }
                    drawable.start()
                } else {
                    lockNow()
                }
            }
            .build()
        imageLoader.enqueue(request)
    }

    private suspend fun loadMediaFromFolder(folderUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(mimeCol)?.startsWith("image/") == true) {
                        uris.add(DocumentsContract.buildDocumentUriUsingTree(folderUri, cursor.getString(idCol)))
                    }
                }
            }
        } catch (e: Exception) { Log.e("GifDreamService", "Error", e) }
        uris
    }

    private fun isGifFile(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri)
        return mimeType == "image/gif" || uri.toString().lowercase().endsWith(".gif")
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        serviceScope.cancel()
    }
}
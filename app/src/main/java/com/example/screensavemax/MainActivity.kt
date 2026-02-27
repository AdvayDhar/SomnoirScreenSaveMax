//MainActivity.kt

package com.example.screensavemax

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var folderUri by mutableStateOf<Uri?>(null)

    private val prefs by lazy {
        getSharedPreferences("screensavemax_prefs", Context.MODE_PRIVATE)
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            folderUri = uri

            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            // Save to SharedPreferences
            prefs.edit().putString("selected_folder_uri", uri.toString()).apply()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* no action needed after result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved folder URI on startup
        val savedUriString = prefs.getString("selected_folder_uri", null)
        if (savedUriString != null) {
            folderUri = Uri.parse(savedUriString)
        }

        // Request device admin if not already granted
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required to lock the screen after the screensaver finishes."
                )
            }
            deviceAdminLauncher.launch(intent)
        }

        setContent {
            var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
            val context = LocalContext.current

            LaunchedEffect(folderUri) {
                folderUri?.let { uri ->
                    val gifsAndImages = loadMediaFromFolder(uri)
                    if (gifsAndImages.isNotEmpty()) {
                        selectedImageUri = gifsAndImages.random()
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Button(onClick = { folderPickerLauncher.launch(null) }) {
                    Text("Select Folder")
                }

                Spacer(modifier = Modifier.height(10.dp))

                folderUri?.let {
                    Text("Folder selected: ${it.lastPathSegment}")
                }

                Spacer(modifier = Modifier.height(20.dp))

                selectedImageUri?.let { uri ->
                    // Create ImageLoader with GIF support
                    val imageLoader = ImageLoader.Builder(context)
                        .components {
                            if (android.os.Build.VERSION.SDK_INT >= 28) {
                                add(ImageDecoderDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                        }
                        .build()

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .build(),
                        contentDescription = null,
                        imageLoader = imageLoader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    )
                } ?: Text("No media selected.")
            }
        }
    }

    private suspend fun loadMediaFromFolder(uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val result = mutableListOf<Uri>()

        contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            val docIdColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(docIdColumn)
                val mime = cursor.getString(mimeColumn)

                if (mime.startsWith("image/") || mime == "image/gif") {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                    result.add(fileUri)
                }
            }
        }
        result
    }
}
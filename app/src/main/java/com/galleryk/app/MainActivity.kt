package com.galleryk.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.security.MessageDigest
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.common.util.Size as Media3Size
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class GalleryMedia(
val uri: Uri,
val isVideo: Boolean,
val duration: Long,
val albumId: String,
val albumName: String,
val dateAdded: Long,
val relativePath: String = "",
val displayName: String = "",
val mimeType: String = ""
)

data class PhotoAlbum(
val id: String,
val name: String,
val media: List<GalleryMedia>
)

private const val RECENTLY_DELETED_PREFS = "gallery_k_recently_deleted"
private const val RECENTLY_DELETED_KEY = "items_json"

private fun loadRecentlyDeleted(context: android.content.Context): List<GalleryMedia> {
    return try {
        val raw = context.getSharedPreferences(RECENTLY_DELETED_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(RECENTLY_DELETED_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val file = File(item.optString("filePath"))
                if (!file.exists() || !file.isFile) continue
                add(
                    GalleryMedia(
                        uri = Uri.fromFile(file),
                        isVideo = item.optBoolean("isVideo"),
                        duration = item.optLong("duration"),
                        albumId = item.optString("albumId", "unknown"),
                        albumName = item.optString("albumName", "Other"),
                        dateAdded = item.optLong("dateAdded"),
                        relativePath = item.optString("relativePath", ""),
                        displayName = item.optString("displayName", file.name),
                        mimeType = item.optString("mimeType", "")
                    )
                )
            }
        }.sortedByDescending { it.dateAdded }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveRecentlyDeleted(context: android.content.Context, items: List<GalleryMedia>) {
    val array = JSONArray()
    items.forEach { media ->
        val filePath = media.uri.path ?: return@forEach
        array.put(
            JSONObject().apply {
                put("filePath", filePath)
                put("isVideo", media.isVideo)
                put("duration", media.duration)
                put("albumId", media.albumId)
                put("albumName", media.albumName)
                put("dateAdded", media.dateAdded)
                put("relativePath", media.relativePath)
                put("displayName", media.displayName)
                put("mimeType", media.mimeType)
            }
        )
    }
    context.getSharedPreferences(RECENTLY_DELETED_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString(RECENTLY_DELETED_KEY, array.toString()).apply()
}

private suspend fun createRecentlyDeletedCopies(
    context: android.content.Context,
    mediaItems: List<GalleryMedia>
): List<GalleryMedia> = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "RecentlyDeleted")
    if (!directory.exists() && !directory.mkdirs()) {
        throw IllegalStateException("Could not create Recently Deleted storage")
    }
    val created = mutableListOf<GalleryMedia>()
    val createdFiles = mutableListOf<File>()
    try {
        mediaItems.forEach { media ->
            val originalName = media.displayName.ifBlank {
                "${if (media.isVideo) "video" else "photo"}_${System.currentTimeMillis()}"
            }
            val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
            val hasExtension = safeName.substringAfterLast('.', "").isNotBlank()
            val fallbackExtension = when (media.mimeType.lowercase()) {
                "video/webm" -> "webm"
                "video/3gpp" -> "3gp"
                "video/x-matroska" -> "mkv"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                "image/gif" -> "gif"
                else -> if (media.isVideo) "mp4" else "jpg"
            }
            val storedName = if (hasExtension) safeName else "$safeName.$fallbackExtension"
            val target = File(directory, "${UUID.randomUUID()}_$storedName")
            createdFiles.add(target)
            context.contentResolver.openInputStream(media.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not read ${media.displayName}")
            if (!target.exists() || target.length() == 0L) {
                throw IllegalStateException("Backup copy was empty")
            }
            created.add(
                media.copy(
                    uri = Uri.fromFile(target),
                    dateAdded = System.currentTimeMillis() / 1000L
                )
            )
        }
        created
    } catch (error: Exception) {
        createdFiles.forEach { it.delete() }
        throw error
    }
}

private suspend fun restoreRecentlyDeletedMedia(
    context: android.content.Context,
    media: GalleryMedia
): Boolean = withContext(Dispatchers.IO) {
    val sourceFile = media.uri.path?.let(::File) ?: return@withContext false
    if (!sourceFile.exists()) return@withContext false
    val collection = if (media.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val mime = media.mimeType.ifBlank {
        if (media.isVideo) "video/mp4" else "image/jpeg"
    }
    val displayName = media.displayName.ifBlank { sourceFile.name.substringAfter('_', sourceFile.name) }
    val fallbackPath = if (media.isVideo) "Movies" else "Pictures"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, media.relativePath.ifBlank { fallbackPath })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val restoredUri = try { resolver.insert(collection, values) } catch (_: Exception) { null }
        ?: return@withContext false
    try {
        resolver.openOutputStream(restoredUri, "w")?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open restored file")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(restoredUri, ready, null, null)
        }
        sourceFile.delete()
        true
    } catch (_: Exception) {
        try { resolver.delete(restoredUri, null, null) } catch (_: Exception) { }
        false
    }
}

private fun permanentlyDeleteRecentlyDeleted(mediaItems: List<GalleryMedia>) {
    mediaItems.forEach { media -> media.uri.path?.let { File(it).delete() } }
}

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContent { GalleryKApp() }
}
}

private fun requiredPermissions(): Array<String> {
return when {
Build.VERSION.SDK_INT >= 34 -> arrayOf(
Manifest.permission.READ_MEDIA_IMAGES,
Manifest.permission.READ_MEDIA_VIDEO,
Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
)
Build.VERSION.SDK_INT >= 33 -> arrayOf(
Manifest.permission.READ_MEDIA_IMAGES,
Manifest.permission.READ_MEDIA_VIDEO
)
else -> arrayOf(
Manifest.permission.READ_EXTERNAL_STORAGE
)
}
}

private fun hasMediaPermission(
context: android.content.Context
): Boolean {
return requiredPermissions().any {
ContextCompat.checkSelfPermission(context, it) ==
PackageManager.PERMISSION_GRANTED
}
}

private suspend fun loadGallery(
context: android.content.Context
): Pair<List<GalleryMedia>, List<PhotoAlbum>> =
withContext(Dispatchers.IO) {

val allMedia = mutableListOf<GalleryMedia>()  

    fun readCollection(collection: Uri, isVideo: Boolean) {  
        val idColumnName = MediaStore.MediaColumns._ID  
        val bucketIdName = MediaStore.Images.ImageColumns.BUCKET_ID  
        val bucketNameName =  
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME  
        val dateName = MediaStore.MediaColumns.DATE_ADDED  

        val baseProjection = mutableListOf(
            idColumnName, bucketIdName, bucketNameName, dateName
        )
        if (isVideo) baseProjection.add(MediaStore.Video.VideoColumns.DURATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseProjection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        baseProjection.add(MediaStore.MediaColumns.DISPLAY_NAME)
        baseProjection.add(MediaStore.MediaColumns.MIME_TYPE)
        val projection = baseProjection.toTypedArray()

        context.contentResolver.query(  
            collection,  
            projection,  
            null,  
            null,  
            "$dateName DESC"  
        )?.use { cursor ->  

            val idIndex = cursor.getColumnIndexOrThrow(idColumnName)  
            val bucketIdIndex =  
                cursor.getColumnIndexOrThrow(bucketIdName)  
            val bucketNameIndex =  
                cursor.getColumnIndexOrThrow(bucketNameName)  
            val dateIndex =  
                cursor.getColumnIndexOrThrow(dateName)  

            val durationIndex = if (isVideo) cursor.getColumnIndexOrThrow(
                MediaStore.Video.VideoColumns.DURATION
            ) else -1
            val relativePathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {  
                val id = cursor.getLong(idIndex)  
                val uri = ContentUris.withAppendedId(collection, id)  
                val bucketId =  
                    cursor.getString(bucketIdIndex) ?: "unknown"  
                val bucketName =  
                    cursor.getString(bucketNameIndex) ?: "Other"  
                val dateAdded = cursor.getLong(dateIndex)  

                val duration = if (isVideo) cursor.getLong(durationIndex) else 0L
                val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex) ?: "" else ""
                val displayName = if (displayNameIndex >= 0) cursor.getString(displayNameIndex) ?: "" else ""
                val mimeType = if (mimeTypeIndex >= 0) cursor.getString(mimeTypeIndex) ?: "" else ""

                allMedia.add(
                    GalleryMedia(
                        uri, isVideo, duration, bucketId, bucketName, dateAdded,
                        relativePath, displayName, mimeType
                    )
                )
            }  
        }  
    }  

    readCollection(  
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,  
        false  
    )  

    readCollection(  
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,  
        true  
    )  

    val sortedMedia = allMedia.sortedByDescending {  
        it.dateAdded  
    }  

    val albumList = sortedMedia  
        .groupBy { it.albumId }  
        .map { (id, items) ->  
            PhotoAlbum(  
                id = id,  
                name = items.firstOrNull()?.albumName ?: "Other",  
                media = items  
            )  
        }  

    Pair(sortedMedia, albumList)  
}

@Composable
fun GalleryKApp() {
val context = LocalContext.current

var allMedia by remember {  
    mutableStateOf(listOf<GalleryMedia>())  
}  
var albums by remember {  
    mutableStateOf(listOf<PhotoAlbum>())  
}  
var selectedIndex by remember { mutableIntStateOf(-1) }
var photoEditorOpen by remember { mutableStateOf(false) }
var cropScreenOpen by remember { mutableStateOf(false) }  
var selectedAlbumId by remember { mutableStateOf<String?>(null) }  
var selectedTab by remember { mutableStateOf("Photos") }  
var permissionGranted by remember { mutableStateOf(false) }  
var loading by remember { mutableStateOf(false) }  
var selectionMode by remember { mutableStateOf(false) }  
var selectedUris by remember { mutableStateOf(setOf<Uri>()) }  
var selectedPreviewMedia by remember { mutableStateOf(listOf<GalleryMedia>()) }
var selectedPreviewIndex by remember { mutableIntStateOf(-1) }
var slideshowActive by remember { mutableStateOf(false) }
var slideshowMedia by remember { mutableStateOf(listOf<GalleryMedia>()) }
var slideshowStartIndex by remember { mutableIntStateOf(0) }
var textStudioMedia by remember { mutableStateOf<GalleryMedia?>(null) }
var textPickerOpen by remember { mutableStateOf(false) }
var textPickerMedia by remember { mutableStateOf(listOf<GalleryMedia>()) }
var refreshKey by remember { mutableIntStateOf(0) }  
var privacyFolderScreen by remember { mutableStateOf(false) }
var showExitPrivacyDialog by remember { mutableStateOf(false) }
var privacyPinUnlocked by remember { mutableStateOf(false) }
var privacyPinChanging by remember { mutableStateOf(false) }
var privacyPickerActive by remember { mutableStateOf(false) }
var recentlyDeletedScreen by remember { mutableStateOf(false) }
var recentlyDeletedItems by remember { mutableStateOf(loadRecentlyDeleted(context)) }
var pendingTrashItems by remember { mutableStateOf(listOf<GalleryMedia>()) }
val pinPrefs = remember { context.getSharedPreferences("gallery_k_privacy_pin", android.content.Context.MODE_PRIVATE) }
var storedPinHash by remember { mutableStateOf(pinPrefs.getString("pin_hash", null)) }
var fingerprintEnabled by remember { mutableStateOf(pinPrefs.getBoolean("fingerprint_enabled", true)) }
DisposableEffect(context) {
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && !privacyPickerActive) { privacyFolderScreen = false; privacyPinUnlocked = false; privacyPinChanging = false }
    }
    lifecycle?.addObserver(observer)
    onDispose { lifecycle?.removeObserver(observer) }
}

val permissionLauncher =  
    rememberLauncherForActivityResult(  
        ActivityResultContracts.RequestMultiplePermissions()  
    ) {  
        permissionGranted = hasMediaPermission(context)  
    }  

val deleteLauncher =  
    rememberLauncherForActivityResult(  
        ActivityResultContracts.StartIntentSenderForResult()  
    ) { result ->  
        if (result.resultCode == Activity.RESULT_OK) {
            recentlyDeletedItems = loadRecentlyDeleted(context)
            Toast.makeText(context, "Moved to Recently Deleted", Toast.LENGTH_SHORT).show()
        } else {
            permanentlyDeleteRecentlyDeleted(pendingTrashItems)
            val pendingPaths = pendingTrashItems.mapNotNull { it.uri.path }.toSet()
            recentlyDeletedItems = recentlyDeletedItems.filter { it.uri.path !in pendingPaths }
            saveRecentlyDeleted(context, recentlyDeletedItems)
            Toast.makeText(context, "Deletion cancelled", Toast.LENGTH_SHORT).show()
        }
        pendingTrashItems = emptyList()
        selectedUris = emptySet()
        selectionMode = false
        refreshKey++  
    }  

LaunchedEffect(Unit) {
    permissionGranted = hasMediaPermission(context)
    recentlyDeletedItems = loadRecentlyDeleted(context)
}  

LaunchedEffect(permissionGranted, refreshKey) {  
    if (permissionGranted) {  
        loading = true  
        try {  
            val result = loadGallery(context)  
            allMedia = result.first  
            albums = result.second  
        } catch (e: Exception) {  
            Toast.makeText(  
                context,  
                "Could not load media: ${e.message}",  
                Toast.LENGTH_LONG  
            ).show()  
        }  
        loading = false  
    }  
}  

val currentAlbum = albums.find {  
    it.id == selectedAlbumId  
}  

val tabMedia = when (selectedTab) {  
    "Videos" -> allMedia.filter { it.isVideo }  
    else -> allMedia.filter { !it.isVideo }  
}  

val displayedMedia = currentAlbum?.media ?: tabMedia  

val appScope = rememberCoroutineScope()

fun requestDelete() {
    if (selectedUris.isEmpty()) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Toast.makeText(context, "Recently Deleted requires Android 11 or newer", Toast.LENGTH_LONG).show()
        return
    }
    val selectedMedia = allMedia.filter { it.uri in selectedUris }
    appScope.launch {
        try {
            val backups = createRecentlyDeletedCopies(context, selectedMedia)
            pendingTrashItems = backups
            recentlyDeletedItems = (recentlyDeletedItems + backups).sortedByDescending { it.dateAdded }
            saveRecentlyDeleted(context, recentlyDeletedItems)
            val request = MediaStore.createDeleteRequest(context.contentResolver, selectedMedia.map { it.uri })
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) {
            permanentlyDeleteRecentlyDeleted(pendingTrashItems)
            val pendingPaths = pendingTrashItems.mapNotNull { it.uri.path }.toSet()
            recentlyDeletedItems = recentlyDeletedItems.filter { it.uri.path !in pendingPaths }
            saveRecentlyDeleted(context, recentlyDeletedItems)
            pendingTrashItems = emptyList()
            Toast.makeText(context, "Could not move items to Recently Deleted: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}  

BackHandler(  
    enabled = privacyFolderScreen || recentlyDeletedScreen || slideshowActive || textPickerOpen || textStudioMedia != null || cropScreenOpen || photoEditorOpen || selectedPreviewMedia.isNotEmpty() || selectionMode ||
        selectedIndex >= 0 ||  
        selectedAlbumId != null ||  
        selectedTab != "Photos"  
) {  
    when {  
        // Back from the Change PIN screen should return to the unlocked Privacy Folder,
        // not trigger the folder's Exit Privacy confirmation.
        recentlyDeletedScreen -> { recentlyDeletedScreen = false }
        textStudioMedia != null -> { textStudioMedia = null }
        textPickerOpen -> { textPickerOpen = false; textPickerMedia = emptyList() }
        slideshowActive -> { slideshowActive = false }
        privacyFolderScreen && privacyPinChanging -> {
            privacyPinChanging = false
            privacyPinUnlocked = true
            showExitPrivacyDialog = false
        }
        privacyFolderScreen -> { showExitPrivacyDialog = true }
        cropScreenOpen -> cropScreenOpen = false
        photoEditorOpen -> photoEditorOpen = false
        selectedPreviewMedia.isNotEmpty() -> { selectedPreviewMedia = emptyList(); selectedPreviewIndex = -1 }
        selectionMode -> {  
            selectionMode = false  
            selectedUris = emptySet()  
        }  
        selectedIndex >= 0 -> selectedIndex = -1  
        selectedAlbumId != null -> selectedAlbumId = null  
        else -> selectedTab = "Photos"  
    }  
}  

MaterialTheme {  
    Surface(  
        modifier = Modifier.fillMaxSize(),  
        color = Color(0xFF101114)  
    ) {  
        if (privacyFolderScreen) {
            if (!privacyPinUnlocked || privacyPinChanging || storedPinHash == null) {
                PrivacyPinGate(
                    existingHash = storedPinHash,
                    changeMode = privacyPinChanging,
                    onCancelChange = { privacyPinChanging = false; privacyPinUnlocked = true },
                    onSuccess = { privacyPinUnlocked = true; privacyPinChanging = false },
                    onPinSaved = { hash -> pinPrefs.edit().putString("pin_hash", hash).apply(); storedPinHash = hash; privacyPinUnlocked = true; privacyPinChanging = false },
                    fingerprintEnabled = fingerprintEnabled
                )
            } else {
                PrivacyFolderScreen(
                    onBack = { showExitPrivacyDialog = true },
                    onOriginalsDeleted = { refreshKey++ },
                    onChangePin = { privacyPinChanging = true; privacyPinUnlocked = false },
                    onPickerStart = { privacyPickerActive = true },
                    onPickerEnd = { privacyPickerActive = false },
                    fingerprintEnabled = fingerprintEnabled,
                    onFingerprintEnabledChange = { enabled -> fingerprintEnabled = enabled; pinPrefs.edit().putBoolean("fingerprint_enabled", enabled).apply() }
                )
            }
        }
        else if (textPickerOpen) {
            TextMediaPickerScreen(
                media = textPickerMedia,
                onBack = { textPickerOpen = false; textPickerMedia = emptyList() },
                onSelect = { item ->
                    textStudioMedia = item
                    textPickerOpen = false
                    textPickerMedia = emptyList()
                }
            )
        }
        else if (textStudioMedia != null) {
            TextStudioScreen(
                media = textStudioMedia!!,
                onBack = { textStudioMedia = null },
                onSaved = {
                    textStudioMedia = null
                    refreshKey++
                }
            )
        }
        else if (slideshowActive) {
            SlideshowScreen(
                media = slideshowMedia,
                startIndex = slideshowStartIndex,
                onBack = { slideshowActive = false }
            )
        }
        else if (recentlyDeletedScreen) {
            RecentlyDeletedScreen(
                items = recentlyDeletedItems,
                onBack = { recentlyDeletedScreen = false },
                onRestore = { selectedItems ->
                    appScope.launch {
                        var restoredCount = 0
                        selectedItems.forEach { item ->
                            if (restoreRecentlyDeletedMedia(context, item)) {
                                recentlyDeletedItems = recentlyDeletedItems.filterNot { it.uri == item.uri }
                                saveRecentlyDeleted(context, recentlyDeletedItems)
                                restoredCount++
                            }
                        }
                        Toast.makeText(context, "Restored $restoredCount item(s)", Toast.LENGTH_SHORT).show()
                        refreshKey++
                    }
                },
                onPermanentlyDelete = { selectedItems ->
                    permanentlyDeleteRecentlyDeleted(selectedItems)
                    val deletedPaths = selectedItems.mapNotNull { it.uri.path }.toSet()
                    recentlyDeletedItems = recentlyDeletedItems.filter { it.uri.path !in deletedPaths }
                    saveRecentlyDeleted(context, recentlyDeletedItems)
                    Toast.makeText(context, "Permanently deleted", Toast.LENGTH_SHORT).show()
                }
            )
        }
        else if (cropScreenOpen && selectedIndex in displayedMedia.indices && !displayedMedia[selectedIndex].isVideo) {
            PhotoCropScreen(
                uri = displayedMedia[selectedIndex].uri,
                onBack = { cropScreenOpen = false },
                onSaved = {
                    Toast.makeText(context, "Cropped photo saved as a copy", Toast.LENGTH_SHORT).show()
                    cropScreenOpen = false
                    refreshKey++
                }
            )
        }
        else if (photoEditorOpen && selectedIndex in displayedMedia.indices && !displayedMedia[selectedIndex].isVideo) {
            PhotoEditorScreen(
                uri = displayedMedia[selectedIndex].uri,
                onBack = { photoEditorOpen = false },
                onSaved = {
                    Toast.makeText(context, "Edited photo saved as a copy", Toast.LENGTH_SHORT).show()
                    photoEditorOpen = false
                    refreshKey++
                }
            )
        }
        else if (selectedPreviewMedia.isNotEmpty()) {
            SelectedMediaPreview(
                mediaItems = selectedPreviewMedia,
                index = selectedPreviewIndex,
                onIndexChange = { selectedPreviewIndex = it },
                onBack = { selectedPreviewMedia = emptyList(); selectedPreviewIndex = -1 }
            )
        }
        else if (  
            selectedIndex >= 0 &&  
            selectedIndex < displayedMedia.size  
        ) {  
            val current = displayedMedia[selectedIndex]  

            if (current.isVideo) {  
                VideoPlayer(
                    uri = current.uri,
                    onBack = { selectedIndex = -1 },
                    onTextClick = { textStudioMedia = current }
                )  
            } else {  
                PhotoViewer(  
                    uri = current.uri,  
                    index = selectedIndex,  
                    total = displayedMedia.size,  
                    onBack = { selectedIndex = -1 },
                    onIndexChange = { selectedIndex = it },
                    onEditClick = { photoEditorOpen = true },
                    onCropClick = { cropScreenOpen = true },
                    onTextClick = { textStudioMedia = current },
                    onSlideshowClick = {
                        val photos = displayedMedia.filter { !it.isVideo }
                        slideshowMedia = photos
                        slideshowStartIndex = photos.indexOfFirst { it.uri == current.uri }.coerceAtLeast(0)
                        slideshowActive = photos.isNotEmpty()
                    }
                )  
            }  
        } else {  
            Column(Modifier.fillMaxSize()) {  
                Row(  
                    Modifier  
                        .fillMaxWidth()  
                        .padding(20.dp),  
                    verticalAlignment = Alignment.CenterVertically  
                ) {  
                    Column(Modifier.weight(1f)) {  
                        Text(  
                            if (selectionMode) {  
                                "${selectedUris.size} Selected"  
                            } else "Gallery K",  
                            color = Color.White,  
                            fontSize = 27.sp,  
                            fontWeight = FontWeight.Bold  
                        )  

                        Text(  
                            if (selectionMode) {  
                                "Select media to manage"  
                            } else {  
                                "Your memories, beautifully organized"  
                            },  
                            color = Color.Gray,  
                            fontSize = 12.sp  
                        )  
                    }  

                    if (selectionMode) {  
                        TextButton(  
                            onClick = {  
                                selectedUris =  
                                    if (  
                                        selectedUris.size ==  
                                        displayedMedia.size  
                                    ) {  
                                        emptySet()  
                                    } else {  
                                        displayedMedia.map { it.uri }.toSet()  
                                    }  
                            }  
                        ) {  
                            Text(  
                                if (  
                                    selectedUris.size ==  
                                    displayedMedia.size  
                                ) "Deselect All" else "Select All",  
                                color = Color.White  
                            )  
                        }  
                    }  
                }  

                if (!selectionMode) {
                    Button(
                        onClick = { privacyFolderScreen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF315C91)
                        )
                    ) {
                        Text("Privacy Folder", color = Color.White)
                    }
                }

                if (selectionMode) {  
                    Row(  
                        Modifier  
                            .fillMaxWidth()  
                            .padding(horizontal = 16.dp),  
                        horizontalArrangement =  
                            Arrangement.spacedBy(12.dp)  
                    ) {  
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = selectedUris.isNotEmpty(),
                            onClick = {
                                selectedPreviewMedia = displayedMedia.filter { it.uri in selectedUris }
                                selectedPreviewIndex = -1
                            }
                        ) { Text("Preview") }

                        Button(  
                            modifier = Modifier.weight(1f),  
                            enabled = selectedUris.isNotEmpty(),  
                            onClick = { requestDelete() }  
                        ) {  
                            Text("Delete (${selectedUris.size})")  
                        }  

                        OutlinedButton(  
                            modifier = Modifier.weight(1f),  
                            onClick = {  
                                selectionMode = false  
                                selectedUris = emptySet()  
                            }  
                        ) {  
                            Text("Cancel")  
                        }  
                    }  
                } else {  
                    Row(  
                        Modifier  
                            .fillMaxWidth()  
                            .padding(horizontal = 12.dp),  
                        horizontalArrangement =  
                            Arrangement.spacedBy(8.dp)  
                    ) {  
                        listOf("Photos", "Videos", "Albums").forEach { tab ->  
                            val active = selectedTab == tab  

                            Box(  
                                Modifier  
                                    .weight(1f)  
                                    .clip(RoundedCornerShape(12.dp))  
                                    .background(  
                                        if (active) Color(0xFF315C91)  
                                        else Color(0xFF24262C)  
                                    )  
                                    .clickable {  
                                        selectedTab = tab  
                                        selectedAlbumId = null  
                                    }  
                                    .padding(vertical = 12.dp),  
                                contentAlignment = Alignment.Center  
                            ) {  
                                Text(  
                                    tab,  
                                    color = Color.White,  
                                    fontWeight = FontWeight.SemiBold  
                                )  
                            }  
                        }  
                    }  
                }  

                Spacer(Modifier.height(14.dp))  

                when {  
                    !permissionGranted -> {  
                        Box(  
                            Modifier.fillMaxSize(),  
                            contentAlignment = Alignment.Center  
                        ) {  
                            Button(  
                                onClick = {  
                                    permissionLauncher.launch(  
                                        requiredPermissions()  
                                    )  
                                }  
                            ) {  
                                Text("Allow Photos and Videos")  
                            }  
                        }  
                    }  

                    loading -> {  
                        Box(  
                            Modifier.fillMaxSize(),  
                            contentAlignment = Alignment.Center  
                        ) {  
                            CircularProgressIndicator()  
                        }  
                    }  

                    selectedTab == "Albums" &&  
                        selectedAlbumId == null -> {  

                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${albums.size} Albums", color = Color.LightGray, modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = allMedia.isNotEmpty(),
                                onClick = {
                                    textPickerMedia = allMedia
                                    textPickerOpen = true
                                }
                            ) { Text("T  Text Studio", color = Color.White) }
                        }  

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumCard(album) { selectedAlbumId = album.id }
                            }
                            item(key = "recently_deleted_album") {
                                RecentlyDeletedAlbumCard(recentlyDeletedItems.size) {
                                    recentlyDeletedScreen = true
                                    selectedUris = emptySet()
                                    selectionMode = false
                                }
                            }
                        }  
                    }  

                    displayedMedia.isEmpty() -> {  
                        Box(  
                            Modifier.fillMaxSize(),  
                            contentAlignment = Alignment.Center  
                        ) {  
                            Text(  
                                if (selectedTab == "Videos") {  
                                    "No videos found"  
                                } else "No photos found",  
                                color = Color.White  
                            )  
                        }  
                    }  

                    else -> {  
                        if (selectedTab == "Albums" && selectedAlbumId != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    enabled = displayedMedia.isNotEmpty(),
                                    onClick = {
                                        textPickerMedia = displayedMedia
                                        textPickerOpen = true
                                    }
                                ) { Text("T  Text", color = Color.White) }
                                TextButton(
                                    enabled = displayedMedia.any { !it.isVideo },
                                    onClick = {
                                        val photosForSlideshow = displayedMedia.filter { !it.isVideo }
                                        slideshowMedia = photosForSlideshow
                                        slideshowStartIndex = 0
                                        slideshowActive = photosForSlideshow.isNotEmpty()
                                    }
                                ) { Text("▶ Slideshow", color = Color.White) }
                            }
                        }
                        Text(  
                            "${displayedMedia.size} ${  
                                if (selectedTab == "Videos") {  
                                    "Videos"  
                                } else "Photos"  
                            }",  
                            color = Color.LightGray,  
                            modifier = Modifier.padding(  
                                start = 20.dp,  
                                bottom = 10.dp  
                            )  
                        )  

                        val selectionGridState = rememberLazyGridState()
                        val selectionScope = rememberCoroutineScope()
                        val selectionModeState = rememberUpdatedState(selectionMode)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // Do not key this pointerInput with selectionMode: turning
                                // selection on after a long-press must not cancel the same touch.
                                .pointerInput(displayedMedia) {
                                var lastY = 0f
                                var accumulatedDy = 0f
                                var scrollDirection = 0
                                var dragDirection = 1
                                var lastRowStartIndex = -1
                                var trackingSelectionDrag = false
                                var autoScrollJob: kotlinx.coroutines.Job? = null
                                var dragSelectedUris = selectedUris

                                fun applyRowAt(y: Float, add: Boolean) {
                                    val visible = selectionGridState.layoutInfo.visibleItemsInfo
                                        .filter { info -> !selectionModeState.value || info.index % 21 != 0 }
                                    if (visible.isEmpty()) return
                                    val hit = visible.minByOrNull { info ->
                                        kotlin.math.abs((info.offset.y + info.size.height / 2f) - y)
                                    } ?: return
                                    // In selection mode, one full-width Select All item is
                                    // inserted before every group of 20 media items.
                                    val mediaIndex = if (selectionModeState.value) {
                                        hit.index - (hit.index / 21 + 1)
                                    } else hit.index
                                    val safeMediaIndex = mediaIndex.coerceIn(0, displayedMedia.lastIndex)
                                    val groupNumber = if (selectionModeState.value) safeMediaIndex / 20 else 0
                                    val indexInGroup = if (selectionModeState.value) safeMediaIndex % 20 else safeMediaIndex
                                    val targetRowNumber = if (selectionModeState.value) {
                                        groupNumber * 7 + indexInGroup / 3
                                    } else safeMediaIndex / 3
                                    val from = if (lastRowStartIndex < 0) targetRowNumber else lastRowStartIndex
                                    val rowNumbers = if (from <= targetRowNumber) {
                                        (from..targetRowNumber).toList()
                                    } else {
                                        (from downTo targetRowNumber).toList()
                                    }
                                    val rowUris = rowNumbers.flatMap { rowNumber ->
                                        val rowGroup = if (selectionModeState.value) rowNumber / 7 else 0
                                        val rowInGroup = if (selectionModeState.value) rowNumber % 7 else rowNumber
                                        val rowStart = if (selectionModeState.value) {
                                            rowGroup * 20 + rowInGroup * 3
                                        } else rowInGroup * 3
                                        val rowEnd = if (selectionModeState.value) {
                                            (rowStart + 3).coerceAtMost((rowGroup + 1) * 20)
                                        } else (rowStart + 3).coerceAtMost(displayedMedia.size)
                                        (rowStart until rowEnd)
                                            .mapNotNull { index -> displayedMedia.getOrNull(index)?.uri }
                                    }.toSet()
                                    val updated = if (add) dragSelectedUris + rowUris else dragSelectedUris - rowUris
                                    if (updated != dragSelectedUris) {
                                        dragSelectedUris = updated
                                        selectedUris = updated
                                    }
                                    lastRowStartIndex = targetRowNumber
                                }

                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start ->
                                        trackingSelectionDrag = true
                                        selectionMode = true
                                        if (trackingSelectionDrag) {
                                            lastY = start.y
                                            accumulatedDy = 0f
                                            scrollDirection = 0
                                            dragDirection = 1
                                            lastRowStartIndex = -1
                                            dragSelectedUris = selectedUris
                                            applyRowAt(start.y, add = true)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        if (trackingSelectionDrag && selectionModeState.value) {
                                            change.consume()
                                            val y = change.position.y
                                            accumulatedDy += y - lastY
                                            if (accumulatedDy >= 10f) {
                                                dragDirection = 1
                                                accumulatedDy = 0f
                                            } else if (accumulatedDy <= -10f) {
                                                dragDirection = -1
                                                accumulatedDy = 0f
                                            }
                                            applyRowAt(y, add = dragDirection > 0)
                                            lastY = y

                                            // Up-scroll can begin from the middle; down-scroll
                                            // begins only in the lower 30% of the screen.
                                            scrollDirection = when {
                                                y <= size.height * 0.50f -> -1
                                                y >= size.height * 0.70f -> 1
                                                else -> 0
                                            }
                                            if (scrollDirection != 0 && autoScrollJob?.isActive != true) {
                                                autoScrollJob = selectionScope.launch {
                                                    while (scrollDirection != 0 && trackingSelectionDrag) {
                                                        val direction = scrollDirection
                                                        val threshold = if (direction < 0) size.height * 0.50f else size.height * 0.70f
                                                        val distance = if (direction < 0) threshold - lastY else lastY - threshold
                                                        val maxDistance = if (direction < 0) threshold else size.height - threshold
                                                        val speed = (7f + (distance / maxDistance.coerceAtLeast(1f)) * 13f)
                                                            .coerceIn(7f, 20f)
                                                        selectionGridState.scrollBy(direction * speed)
                                                        applyRowAt(lastY, add = dragDirection > 0)
                                                        kotlinx.coroutines.delay(16L)
                                                    }
                                                }
                                            } else if (scrollDirection == 0) {
                                                autoScrollJob?.cancel()
                                                autoScrollJob = null
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        trackingSelectionDrag = false
                                        scrollDirection = 0
                                        autoScrollJob?.cancel()
                                        autoScrollJob = null
                                    },
                                    onDragCancel = {
                                        trackingSelectionDrag = false
                                        scrollDirection = 0
                                        autoScrollJob?.cancel()
                                        autoScrollJob = null
                                    }
                                )
                            }
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(3.dp),
                                state = selectionGridState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val groupSize = 20
                                val groupCount = if (selectionMode) {
                                    (displayedMedia.size + groupSize - 1) / groupSize
                                } else 0
                                val totalGridItems = displayedMedia.size + groupCount

                                items(
                                    count = totalGridItems,
                                    key = { gridIndex ->
                                        if (selectionMode && gridIndex % 21 == 0) {
                                            "select_all_group_${gridIndex / 21}"
                                        } else {
                                            val mediaIndex = if (selectionMode) {
                                                gridIndex - (gridIndex / 21 + 1)
                                            } else gridIndex
                                            displayedMedia[mediaIndex].uri.toString()
                                        }
                                    },
                                    span = { gridIndex ->
                                        if (selectionMode && gridIndex % 21 == 0) {
                                            GridItemSpan(maxLineSpan)
                                        } else GridItemSpan(1)
                                    }
                                ) { gridIndex ->
                                    if (selectionMode && gridIndex % 21 == 0) {
                                        val groupNumber = gridIndex / 21
                                        val startIndex = groupNumber * groupSize
                                        val endExclusive = (startIndex + groupSize)
                                            .coerceAtMost(displayedMedia.size)
                                        val groupUris = displayedMedia
                                            .subList(startIndex, endExclusive)
                                            .map { it.uri }
                                        val allGroupSelected = groupUris.all { it in selectedUris }

                                        TextButton(
                                            onClick = {
                                                selectedUris = if (allGroupSelected) {
                                                    selectedUris - groupUris.toSet()
                                                } else {
                                                    selectedUris + groupUris
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (allGroupSelected) {
                                                    "Deselect All (${startIndex + 1}–$endExclusive)"
                                                } else {
                                                    "Select All (${startIndex + 1}–$endExclusive)"
                                                },
                                                color = Color.White
                                            )
                                        }
                                    } else {
                                        val mediaIndex = if (selectionMode) {
                                            gridIndex - (gridIndex / 21 + 1)
                                        } else gridIndex
                                        val media = displayedMedia[mediaIndex]
                                        MediaThumbnail(
                                            media = media,
                                            selected = media.uri in selectedUris,
                                            onClick = {
                                                if (selectionMode) {
                                                    selectedUris =
                                                        if (media.uri in selectedUris) {
                                                            selectedUris - media.uri
                                                        } else {
                                                            selectedUris + media.uri
                                                        }
                                                    if (selectedUris.isEmpty()) selectionMode = false
                                                } else {
                                                    selectedIndex = mediaIndex
                                                }
                                            },
                                            onLongClick = null
                                        )
                                    }
                                }
                            }
                        }  
                    }  
                }  
            }  
        }  
    }  

    if (showExitPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showExitPrivacyDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF202228),
            title = {
                Text(
                    "Exit Privacy?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Are you sure you want to leave your Privacy Folder?",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitPrivacyDialog = false
                        privacyFolderScreen = false
                        privacyPinUnlocked = false
                        privacyPinChanging = false
                        privacyPickerActive = false
                    }
                ) {
                    Text("Exit", color = Color(0xFF5B9BEB), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitPrivacyDialog = false }) {
                    Text("Stay", color = Color.LightGray, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}
}

@Composable
private fun RecentlyDeletedAlbumCard(count: Int, onClick: () -> Unit) {
    Column(
        Modifier.padding(5.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF24262C)).clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF30333A)),
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = 42.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Recently Deleted", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("$count items", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun RecentlyDeletedScreen(
    items: List<GalleryMedia>,
    onBack: () -> Unit,
    onRestore: (List<GalleryMedia>) -> Unit,
    onPermanentlyDelete: (List<GalleryMedia>) -> Unit
) {
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    var viewingIndex by remember { mutableIntStateOf(-1) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val selectionModeState = rememberUpdatedState(selectionMode)
    val selectedState = rememberUpdatedState(selectedUris)
    val latestItems = rememberUpdatedState(items)

    BackHandler(enabled = true) {
        when {
            viewingIndex >= 0 -> viewingIndex = -1
            selectionMode -> { selectionMode = false; selectedUris = emptySet() }
            else -> onBack()
        }
    }

    if (viewingIndex in items.indices) {
        val current = items[viewingIndex]
        if (current.isVideo) {
            VideoPlayer(uri = current.uri, onBack = { viewingIndex = -1 })
        } else {
            PhotoViewer(
                uri = current.uri,
                index = viewingIndex,
                total = items.size,
                onBack = { viewingIndex = -1 },
                onIndexChange = { if (it in items.indices) viewingIndex = it },
                showActions = false
            )
        }
    } else {
        Column(Modifier.fillMaxSize().background(Color(0xFF101114))) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable {
                    if (selectionMode) { selectionMode = false; selectedUris = emptySet() } else onBack()
                })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (selectionMode) "${selectedUris.size} Selected" else "Recently Deleted",
                        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Items stay here until you restore or delete them", color = Color.Gray, fontSize = 11.sp)
                }
                if (items.isNotEmpty() && !selectionMode) {
                    TextButton(onClick = { selectionMode = true; selectedUris = items.map { it.uri }.toSet() }) {
                        Text("Delete All", color = Color.White)
                    }
                } else if (selectionMode) {
                    TextButton(onClick = {
                        selectedUris = if (selectedUris.size == items.size) emptySet() else items.map { it.uri }.toSet()
                    }) { Text(if (selectedUris.size == items.size) "Deselect" else "Select All", color = Color.White) }
                }
            }

            if (selectionMode) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = selectedUris.isNotEmpty(),
                        onClick = { onRestore(items.filter { it.uri in selectedUris }); selectionMode = false; selectedUris = emptySet() }
                    ) { Text("Restore (${selectedUris.size})") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = selectedUris.isNotEmpty(),
                        onClick = { showDeleteConfirmation = true }
                    ) { Text("Delete (${selectedUris.size})") }
                    TextButton(onClick = { selectionMode = false; selectedUris = emptySet() }) { Text("Cancel", color = Color.White) }
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗑", fontSize = 48.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Recently Deleted is empty", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Box(
                    Modifier.fillMaxSize().pointerInput(items) {
                        var lastY = 0f
                        var lastRow = -1
                        var direction = 1
                        var tracking = false
                        var scrollDirection = 0
                        var dragSelection = selectedState.value
                        var autoScrollJob: kotlinx.coroutines.Job? = null
                        fun applyAt(y: Float, add: Boolean) {
                            val visible = gridState.layoutInfo.visibleItemsInfo
                            if (visible.isEmpty()) return
                            val hit = visible.minByOrNull { kotlin.math.abs((it.offset.y + it.size.height / 2f) - y) } ?: return
                            val row = hit.index / 3
                            val from = if (lastRow < 0) row else lastRow
                            val rows = if (from <= row) (from..row) else (from downTo row)
                            val rowUris = rows.flatMap { r ->
                                val start = r * 3
                                (start until (start + 3).coerceAtMost(latestItems.value.size))
                                    .mapNotNull { latestItems.value.getOrNull(it)?.uri }
                            }.toSet()
                            val updated = if (add) dragSelection + rowUris else dragSelection - rowUris
                            if (updated != dragSelection) {
                                dragSelection = updated
                                selectedUris = updated
                            }
                            lastRow = row
                        }
                        detectDragGestures(
                            onDragStart = { start ->
                                tracking = selectionModeState.value
                                if (tracking) {
                                    lastY = start.y; lastRow = -1; direction = 1
                                    dragSelection = selectedState.value
                                    applyAt(start.y, true)
                                }
                            },
                            onDrag = { change, _ ->
                                if (tracking && selectionModeState.value) {
                                    change.consume()
                                    val y = change.position.y
                                    if (y - lastY > 8f) direction = 1 else if (y - lastY < -8f) direction = -1
                                    applyAt(y, direction > 0)
                                    lastY = y
                                    scrollDirection = when {
                                        y < size.height * 0.18f -> -1
                                        y > size.height * 0.82f -> 1
                                        else -> 0
                                    }
                                    if (scrollDirection != 0 && autoScrollJob?.isActive != true) {
                                        autoScrollJob = scope.launch {
                                            while (scrollDirection != 0 && tracking) {
                                                gridState.scrollBy(scrollDirection * 22f)
                                                delay(16)
                                            }
                                        }
                                    }
                                }
                            },
                            onDragEnd = { tracking = false; scrollDirection = 0; autoScrollJob?.cancel() },
                            onDragCancel = { tracking = false; scrollDirection = 0; autoScrollJob?.cancel() }
                        )
                    }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(3.dp)
                    ) {
                        items(items.size, key = { items[it].uri.toString() }) { index ->
                            val media = items[index]
                            MediaThumbnail(
                                media = media,
                                selected = media.uri in selectedUris,
                                onClick = {
                                    if (selectionMode) {
                                        selectedUris = if (media.uri in selectedUris) selectedUris - media.uri else selectedUris + media.uri
                                        if (selectedUris.isEmpty()) selectionMode = false
                                    } else viewingIndex = index
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selectedUris = selectedUris + media.uri
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Permanently delete selected items?", color = Color.White) },
            text = { Text("These files will be removed from Recently Deleted and cannot be restored.", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    val deleting = items.filter { it.uri in selectedUris }
                    showDeleteConfirmation = false
                    onPermanentlyDelete(deleting)
                    selectedUris = emptySet()
                    selectionMode = false
                }) { Text("Delete permanently", color = Color(0xFFFF7777)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel", color = Color.White) } },
            containerColor = Color(0xFF202228)
        )
    }
}

@Composable
private fun AlbumCard(
album: PhotoAlbum,
onClick: () -> Unit
) {
Column(
Modifier
.padding(5.dp)
.clip(RoundedCornerShape(12.dp))
.background(Color(0xFF24262C))
.clickable(onClick = onClick)
.padding(8.dp)
) {
album.media.firstOrNull()?.let { first ->
MediaThumbnail(
media = first,
selected = false,
onClick = onClick,
onLongClick = onClick
)
}

Spacer(Modifier.height(8.dp))  

    Text(  
        album.name,  
        color = Color.White,  
        fontWeight = FontWeight.SemiBold,  
        maxLines = 1  
    )  

    Text(  
        "${album.media.size} items",  
        color = Color.Gray,  
        fontSize = 12.sp  
    )  
}

}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
media: GalleryMedia,
selected: Boolean,
onClick: () -> Unit,
onLongClick: (() -> Unit)?
) {
val context = LocalContext.current

val bitmap by produceState<Bitmap?>(  
    initialValue = null,  
    key1 = media.uri  
) {  
    value = withContext(Dispatchers.IO) {  
        try {  
            if (media.uri.scheme == "file") {
                val file = media.uri.path?.let(::File) ?: return@withContext null
                if (media.isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(256, 256), null)
                } else {
                    context.contentResolver.openInputStream(media.uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(media.uri, android.util.Size(256, 256), null)
            } else {
                context.contentResolver.openInputStream(media.uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }  
        } catch (_: Exception) {  
            null  
        }  
    }  
}  

Box(  
    Modifier  
        .aspectRatio(1f)  
        .padding(2.dp)  
        .background(Color(0xFF292B30))  
        .combinedClickable(  
            onClick = onClick,  
            onLongClick = onLongClick  
        ),  
    contentAlignment = Alignment.Center  
) {  
    if (bitmap != null) {  
        Image(  
            bitmap = bitmap!!.asImageBitmap(),  
            contentDescription =  
                if (media.isVideo) "Video thumbnail"  
                else "Photo thumbnail",  
            modifier = Modifier.fillMaxSize(),  
            contentScale = ContentScale.Crop  
        )  
    } else {  
        CircularProgressIndicator(  
            modifier = Modifier.size(18.dp),  
            strokeWidth = 2.dp  
        )  
    }  

    if (media.isVideo) {  
        Box(  
            Modifier  
                .align(Alignment.Center)  
                .background(  
                    Color(0x99000000),  
                    RoundedCornerShape(50)  
                )  
                .padding(horizontal = 9.dp, vertical = 5.dp)  
        ) {  
            Text(  
                "▶",  
                color = Color.White,  
                fontSize = 16.sp  
            )  
        }  

        Text(  
            formatDuration(media.duration),  
            color = Color.White,  
            fontSize = 10.sp,  
            modifier = Modifier  
                .align(Alignment.BottomEnd)  
                .padding(5.dp)  
                .background(  
                    Color(0xAA000000),  
                    RoundedCornerShape(4.dp)  
                )  
                .padding(horizontal = 4.dp, vertical = 2.dp)  
        )  
    }  

    if (selected) {  
        Box(  
            Modifier  
                .fillMaxSize()  
                .background(Color(0x66000000)),  
            contentAlignment = Alignment.TopEnd  
        ) {  
            Text(  
                "✓",  
                color = Color.White,  
                fontSize = 22.sp,  
                fontWeight = FontWeight.Bold,  
                modifier = Modifier  
                    .padding(6.dp)  
                    .background(  
                        Color(0xFF2878D0),  
                        RoundedCornerShape(50)  
                    )  
                    .padding(horizontal = 7.dp, vertical = 2.dp)  
            )  
        }  
    }  
}

}

private fun formatDuration(durationMs: Long): String {
val totalSeconds = durationMs / 1000
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60

return if (hours > 0) {  
    String.format("%d:%02d:%02d", hours, minutes, seconds)  
} else {  
    String.format("%d:%02d", minutes, seconds)  
}

}

@Composable
private fun SelectedMediaPreview(
    mediaItems: List<GalleryMedia>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val viewingItem = index in mediaItems.indices
    BackHandler(enabled = viewingItem) {
        onIndexChange(-1)
    }

    if (viewingItem) {
        val current = mediaItems[index]
        if (current.isVideo) {
            VideoPlayer(
                uri = current.uri,
                onBack = { onIndexChange(-1) }
            )
        } else {
            PhotoViewer(
                uri = current.uri,
                index = index,
                total = mediaItems.size,
                onBack = { onIndexChange(-1) },
                onIndexChange = { nextIndex ->
                    if (nextIndex in mediaItems.indices) onIndexChange(nextIndex)
                }
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Preview (${mediaItems.size})",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onBack) {
                    Text("Close", color = Color.White)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(3.dp)
            ) {
                items(
                    items = mediaItems,
                    key = { it.uri.toString() }
                ) { media ->
                    MediaThumbnail(
                        media = media,
                        selected = false,
                        onClick = { onIndexChange(mediaItems.indexOf(media)) },
                        onLongClick = { onIndexChange(mediaItems.indexOf(media)) }
                    )
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF315C91)
                )
            ) {
                Text("Back", color = Color.White)
            }
        }
    }
}

@Composable
private fun SlideshowScreen(
    media: List<GalleryMedia>,
    startIndex: Int,
    onBack: () -> Unit
) {
    val photos = remember(media) { media.filter { !it.isVideo } }
    var currentIndex by remember(photos, startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    }
    var isPlaying by remember { mutableStateOf(true) }
    var intervalSeconds by remember { mutableIntStateOf(3) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(true) }

    fun nextPhoto() {
        if (photos.isEmpty()) return
        currentIndex = when {
            shuffle && photos.size > 1 -> {
                var next = currentIndex
                while (next == currentIndex) next = kotlin.random.Random.nextInt(photos.size)
                next
            }
            currentIndex < photos.lastIndex -> currentIndex + 1
            repeat -> 0
            else -> {
                isPlaying = false
                currentIndex
            }
        }
    }

    LaunchedEffect(isPlaying, currentIndex, intervalSeconds, shuffle, repeat, photos.size) {
        if (isPlaying && photos.isNotEmpty()) {
            delay(intervalSeconds * 1000L)
            nextPhoto()
        }
    }

    BackHandler(onBack = onBack)

    if (photos.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No photos available for slideshow", color = Color.White)
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PhotoViewer(
                uri = photos[currentIndex].uri,
                index = currentIndex,
                total = photos.size,
                onBack = onBack,
                showHeader = false,
                showActions = false,
                onIndexChange = { next -> if (next in photos.indices) currentIndex = next }
            )
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color(0x99000000))
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = Color.White, fontSize = 32.sp,
                        modifier = Modifier.clickable(onClick = onBack))
                    Spacer(Modifier.width(14.dp))
                    Text("Slideshow  ${currentIndex + 1}/${photos.size}",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xEE17181D))
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = currentIndex > 0 || repeat || shuffle,
                        onClick = { currentIndex = if (currentIndex > 0) currentIndex - 1 else photos.lastIndex }
                    ) { Text("Previous", color = Color.White) }
                    Button(onClick = { isPlaying = !isPlaying }) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    TextButton(
                        enabled = currentIndex < photos.lastIndex || repeat || shuffle,
                        onClick = { nextPhoto() }
                    ) { Text("Next", color = Color.White) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        intervalSeconds = when (intervalSeconds) { 3 -> 5; 5 -> 10; else -> 3 }
                    }) { Text("${intervalSeconds}s", color = Color.White) }
                    TextButton(onClick = { shuffle = !shuffle }) {
                        Text(if (shuffle) "Shuffle ON" else "Shuffle OFF", color = Color.White)
                    }
                    TextButton(onClick = { repeat = !repeat }) {
                        Text(if (repeat) "Repeat ON" else "Repeat OFF", color = Color.White)
                    }
                }
            }
        }
    }
}


private data class GalleryTextLayer(
    val id: Int,
    val text: String,
    val color: Int = android.graphics.Color.WHITE,
    val fontSize: Float = 34f,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val shadow: Boolean = true,
    val outline: Boolean = false,
    val colorMode: String = "Single",
    val letterColors: Map<Int, Int> = emptyMap(),
    val lineColors: Map<Int, Int> = emptyMap(),
    val x: Float = 0.5f,
    val y: Float = 0.72f,
    val startMs: Long = 0L,
    val endMs: Long = Long.MAX_VALUE,
    val fontFamily: String = "sans-serif"
)

private val galleryTextPalette = buildList {
    // Main colors first, followed by their lighter and darker shades.
    addAll(listOf(
        android.graphics.Color.RED,
        android.graphics.Color.GREEN,
        android.graphics.Color.BLUE,
        android.graphics.Color.MAGENTA,
        android.graphics.Color.YELLOW,
        android.graphics.Color.rgb(255, 145, 0),
        android.graphics.Color.CYAN,
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        android.graphics.Color.DKGRAY,
        android.graphics.Color.GRAY,
        android.graphics.Color.LTGRAY
    ))

    // Multiple saturation and brightness levels for a wide range of colors.
    val hues = floatArrayOf(0f, 25f, 45f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f)
    val saturations = floatArrayOf(1f, 0.75f, 0.5f, 0.3f)
    val values = floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.4f)
    hues.forEach { hue ->
        saturations.forEach { saturation ->
            values.forEach { brightness ->
                add(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
            }
        }
    }

    // Neutral shades from near-white through charcoal and black.
    for (level in 240 downTo 16 step 8) {
        add(android.graphics.Color.rgb(level, level, level))
    }
}

private fun buildGalleryPreviewText(layer: GalleryTextLayer): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var globalCharIndex = 0
        layer.text.split("\n").forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) {
                append("\n")
                globalCharIndex++
            }
            when (layer.colorMode) {
                "Letters" -> line.forEach { character ->
                    val characterColor = layer.letterColors[globalCharIndex]
                        ?: layer.color
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(characterColor))) {
                        append(character)
                    }
                    globalCharIndex++
                }
                "Lines" -> {
                    val lineColor = layer.lineColors[lineIndex]
                        ?: layer.color
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(lineColor))) { append(line) }
                    globalCharIndex += line.length + 1
                }
                else -> {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(layer.color))) { append(line) }
                    globalCharIndex += line.length + 1
                }
            }
        }
    }

private fun drawGalleryTextLayer(
    canvas: android.graphics.Canvas,
    layer: GalleryTextLayer,
    width: Int,
    height: Int,
    timeMs: Long,
    useTiming: Boolean
) {
    if (layer.text.isBlank()) return
    if (useTiming && (timeMs < layer.startMs || timeMs > layer.endMs)) return

    val style = (if (layer.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) or
        (if (layer.italic) android.graphics.Typeface.ITALIC else 0)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (width * layer.fontSize / 420f).coerceAtLeast(18f)
        textAlign = android.graphics.Paint.Align.LEFT
        typeface = android.graphics.Typeface.create(layer.fontFamily, style)
        color = layer.color
    }
    val lines = layer.text.split("\n")
    val lineHeight = paint.textSize * 1.22f
    val centerX = (width * layer.x).coerceIn(0f, width.toFloat())
    val centerY = (height * layer.y).coerceIn(0f, height.toFloat())
    val firstBaseline = centerY - (lines.size - 1) * lineHeight / 2f

    var globalCharIndex = 0
    lines.forEachIndexed { lineIndex, line ->
        val baseline = firstBaseline + lineIndex * lineHeight
        val lineWidth = paint.measureText(line)
        if (layer.colorMode == "Letters") {
            var x = centerX - lineWidth / 2f
            line.forEachIndexed { charIndex, character ->
                val charText = character.toString()
                val charWidth = paint.measureText(charText)
                val charColor = layer.letterColors[globalCharIndex + charIndex]
                    ?: layer.color
                paint.shader = null
                paint.color = charColor
                if (layer.shadow) paint.setShadowLayer(paint.textSize * 0.10f, 2f, 3f, android.graphics.Color.BLACK)
                if (layer.outline) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = (paint.textSize * 0.075f).coerceAtLeast(2f)
                    paint.color = android.graphics.Color.BLACK
                    canvas.drawText(charText, x, baseline, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = charColor
                }
                canvas.drawText(charText, x, baseline, paint)
                paint.clearShadowLayer()
                x += charWidth
            }
        } else {
            val lineColor = if (layer.colorMode == "Lines") {
                layer.lineColors[lineIndex] ?: layer.color
            } else layer.color
            paint.color = lineColor
            paint.shader = null
            if (layer.colorMode == "Gradient" && line.isNotEmpty()) {
                paint.shader = android.graphics.LinearGradient(
                    0f, baseline - paint.textSize, width.toFloat(), baseline,
                    intArrayOf(android.graphics.Color.RED, android.graphics.Color.YELLOW, android.graphics.Color.CYAN, android.graphics.Color.MAGENTA),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            if (layer.shadow) paint.setShadowLayer(paint.textSize * 0.10f, 2f, 3f, android.graphics.Color.BLACK)
            if (layer.outline) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = (paint.textSize * 0.075f).coerceAtLeast(2f)
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(line, centerX - lineWidth / 2f, baseline, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = lineColor
            }
            canvas.drawText(line, centerX - lineWidth / 2f, baseline, paint)
            paint.clearShadowLayer()
            paint.shader = null
            paint.style = android.graphics.Paint.Style.FILL
        }
        globalCharIndex += line.length + 1
    }
}

@OptIn(UnstableApi::class)
private class GalleryTimedTextOverlay(
    private val layers: List<GalleryTextLayer>
) : BitmapOverlay() {
    private var overlayWidth: Int = 1
    private var overlayHeight: Int = 1
    private var overlayBitmap: Bitmap? = null

    override fun configure(videoSize: Media3Size) {
        super.configure(videoSize)
        val newWidth = videoSize.width.coerceAtLeast(1)
        val newHeight = videoSize.height.coerceAtLeast(1)
        if (newWidth != overlayWidth || newHeight != overlayHeight || overlayBitmap == null) {
            overlayBitmap?.recycle()
            overlayWidth = newWidth
            overlayHeight = newHeight
            overlayBitmap = Bitmap.createBitmap(overlayWidth, overlayHeight, Bitmap.Config.ARGB_8888)
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bitmap = overlayBitmap ?: Bitmap.createBitmap(
            overlayWidth, overlayHeight, Bitmap.Config.ARGB_8888
        ).also { overlayBitmap = it }
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = android.graphics.Canvas(bitmap)
        val timeMs = presentationTimeUs / 1000L
        layers.forEach { layer ->
            drawGalleryTextLayer(canvas, layer, overlayWidth, overlayHeight, timeMs, true)
        }
        return bitmap
    }

    override fun release() {
        overlayBitmap?.recycle()
        overlayBitmap = null
        super.release()
    }
}

@Composable
private fun TextMediaPickerScreen(
    media: List<GalleryMedia>,
    onBack: () -> Unit,
    onSelect: (GalleryMedia) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text("Choose Photo or Video", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Text("Select a file to open Text Studio", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(3.dp)) {
            items(media, key = { it.uri.toString() }) { item ->
                MediaThumbnail(
                    media = item,
                    selected = false,
                    onClick = { onSelect(item) },
                    onLongClick = { onSelect(item) }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TextStudioScreen(
    media: GalleryMedia,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layers = remember(media.uri) {
        mutableStateListOf(GalleryTextLayer(id = 1, text = "Your Text"))
    }
    var selectedLayerId by remember(media.uri) { mutableIntStateOf(1) }
    var nextLayerId by remember(media.uri) { mutableIntStateOf(2) }
    var selectedCharacterIndex by remember(media.uri) { mutableIntStateOf(0) }
    var selectedLineIndex by remember(media.uri) { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var durationMs by remember(media.uri) { mutableLongStateOf(media.duration.coerceAtLeast(1000L)) }
    var previewPositionMs by remember(media.uri) { mutableLongStateOf(0L) }
    var previewBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var mediaWidthPx by remember(media.uri) { mutableIntStateOf(1) }
    var mediaHeightPx by remember(media.uri) { mutableIntStateOf(1) }
    val player = remember(media.uri, media.isVideo) {
        if (media.isVideo) ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(media.uri))
            prepare()
            playWhenReady = false
        } else null
    }
    val selectedIndex = layers.indexOfFirst { it.id == selectedLayerId }.coerceAtLeast(0)
    val current = layers.getOrNull(selectedIndex) ?: layers.first()
    fun updateLayer(transform: (GalleryTextLayer) -> GalleryTextLayer) {
        val index = layers.indexOfFirst { it.id == selectedLayerId }
        if (index >= 0) layers[index] = transform(layers[index])
    }

    LaunchedEffect(media.uri) {
        if (media.isVideo) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, media.uri)
                durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(1000L) ?: durationMs
                mediaWidthPx = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.coerceAtLeast(1) ?: mediaWidthPx
                mediaHeightPx = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.coerceAtLeast(1) ?: mediaHeightPx
                previewBitmap = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                previewBitmap?.let { frame ->
                    if (mediaWidthPx <= 1 || mediaHeightPx <= 1) {
                        mediaWidthPx = frame.width
                        mediaHeightPx = frame.height
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } else {
            previewBitmap = withContext(Dispatchers.IO) {
                try { context.contentResolver.openInputStream(media.uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }
                catch (_: Exception) { null }
            }
            previewBitmap?.let { bitmap ->
                mediaWidthPx = bitmap.width.coerceAtLeast(1)
                mediaHeightPx = bitmap.height.coerceAtLeast(1)
            }
        }
    }

    LaunchedEffect(player) {
        if (player != null) {
            while (true) {
                previewPositionMs = player.currentPosition.coerceAtLeast(0L)
                delay(120L)
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Text Studio", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (media.isVideo) "Video Text Editor" else "Photo Text Editor", color = Color.Gray, fontSize = 11.sp)
            }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (exporting) Color.DarkGray else Color(0xFF315C91))
                    .clickable(enabled = !exporting && layers.any { it.text.isNotBlank() }) {
                        exporting = true
                        val exportLayers = layers.toList()
                        scope.launch {
                            if (!media.isVideo) {
                                val saved = withContext(Dispatchers.IO) {
                                    var source: Bitmap? = null
                                    var output: Bitmap? = null
                                    var target: Uri? = null
                                    try {
                                        source = context.contentResolver.openInputStream(media.uri)?.use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        } ?: return@withContext false
                                        output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(output)
                                        canvas.drawBitmap(source, 0f, 0f, null)
                                        exportLayers.forEach { layer ->
                                            drawGalleryTextLayer(canvas, layer, output.width, output.height, 0L, false)
                                        }
                                        val values = ContentValues().apply {
                                            put(MediaStore.Images.Media.DISPLAY_NAME, "GalleryK_Text_${System.currentTimeMillis()}.jpg")
                                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery K")
                                                put(MediaStore.Images.Media.IS_PENDING, 1)
                                            }
                                        }
                                        target = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                            ?: return@withContext false
                                        val wrote = context.contentResolver.openOutputStream(target!!)?.use {
                                            output.compress(Bitmap.CompressFormat.JPEG, 95, it)
                                        } ?: false
                                        if (!wrote) {
                                            context.contentResolver.delete(target!!, null, null)
                                            return@withContext false
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                                            context.contentResolver.update(target!!, ready, null, null)
                                        }
                                        true
                                    } catch (_: Exception) {
                                        target?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                                        false
                                    } finally {
                                        source?.recycle()
                                        output?.recycle()
                                    }
                                }
                                exporting = false
                                if (saved) {
                                    Toast.makeText(context, "Text photo saved in Gallery K", Toast.LENGTH_SHORT).show()
                                    onSaved()
                                } else Toast.makeText(context, "Could not save photo", Toast.LENGTH_LONG).show()
                            } else {
                                val outputFile = File(context.cacheDir, "galleryk_text_${System.currentTimeMillis()}.mp4")
                                try {
                                    val overlay = GalleryTimedTextOverlay(exportLayers)
                                    val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(media.uri))
                                        .setEffects(Effects(emptyList(), listOf(OverlayEffect(mutableListOf<TextureOverlay>(overlay)))))
                                        .build()
                                    val transformer = Transformer.Builder(context)
                                        .addListener(object : Transformer.Listener {
                                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                scope.launch {
                                                    val saved = withContext(Dispatchers.IO) {
                                                        var target: Uri? = null
                                                        try {
                                                            val values = ContentValues().apply {
                                                                put(MediaStore.Video.Media.DISPLAY_NAME, "GalleryK_Text_${System.currentTimeMillis()}.mp4")
                                                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Gallery K")
                                                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                                                }
                                                            }
                                                            target = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                                                ?: return@withContext false
                                                            context.contentResolver.openOutputStream(target!!)?.use { output ->
                                                                outputFile.inputStream().use { input -> input.copyTo(output) }
                                                            } ?: return@withContext false
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                                                                context.contentResolver.update(target!!, ready, null, null)
                                                            }
                                                            true
                                                        } catch (_: Exception) {
                                                            target?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                                                            false
                                                        } finally { outputFile.delete() }
                                                    }
                                                    exporting = false
                                                    if (saved) {
                                                        Toast.makeText(context, "Text video exported to Movies/Gallery K", Toast.LENGTH_LONG).show()
                                                        onSaved()
                                                    } else Toast.makeText(context, "Video export finished but saving failed", Toast.LENGTH_LONG).show()
                                                }
                                            }

                                            override fun onError(
                                                composition: Composition,
                                                exportResult: ExportResult,
                                                exportException: ExportException
                                            ) {
                                                outputFile.delete()
                                                exporting = false
                                                Toast.makeText(context, "Video export failed: ${exportException.message}", Toast.LENGTH_LONG).show()
                                            }
                                        })
                                        .build()
                                    transformer.start(editedItem, outputFile.absolutePath)
                                } catch (error: Exception) {
                                    outputFile.delete()
                                    exporting = false
                                    Toast.makeText(context, "Could not start video export: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (exporting) "Exporting…" else "Save",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val aspectRatio = (mediaWidthPx.toFloat() / mediaHeightPx.toFloat()).coerceIn(0.1f, 10f)
            val availableWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val availableHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
            val fittedWidthPx = minOf(availableWidthPx, availableHeightPx * aspectRatio).coerceAtLeast(1f)
            val fittedHeightPx = (fittedWidthPx / aspectRatio).coerceAtMost(availableHeightPx).coerceAtLeast(1f)
            val fittedWidth = with(density) { fittedWidthPx.toDp() }
            val fittedHeight = with(density) { fittedHeightPx.toDp() }

            Box(
                Modifier.width(fittedWidth).height(fittedHeight)
                    .pointerInput(selectedLayerId, fittedWidthPx, fittedHeightPx) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val activeId = selectedLayerId
                            val index = layers.indexOfFirst { it.id == activeId }
                            if (index >= 0) {
                                val old = layers[index]
                                layers[index] = old.copy(
                                    x = (old.x + pan.x / fittedWidthPx).coerceIn(0f, 1f),
                                    y = (old.y + pan.y / fittedHeightPx).coerceIn(0f, 1f),
                                    fontSize = (old.fontSize * zoomChange).coerceAtLeast(1f)
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (media.isVideo && player != null) {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                this.player = player
                                useController = true
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Text editor photo preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }

                layers.filter { it.text.isNotBlank() && (!media.isVideo || (previewPositionMs >= it.startMs && previewPositionMs <= it.endMs)) }
                    .forEach { layer ->
                        var textLayout by remember(layer.id) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                        val previewFontSize = (layer.fontSize * fittedWidthPx / 420f / density.density)
                            .coerceAtLeast(1f / density.density)
                        Text(
                            text = buildGalleryPreviewText(layer),
                            color = Color(layer.color),
                            fontSize = previewFontSize.sp,
                            fontWeight = if (layer.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (layer.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                            fontFamily = when (layer.fontFamily) {
                                "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                                "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                else -> androidx.compose.ui.text.font.FontFamily.SansSerif
                            },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            onTextLayout = { textLayout = it },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = fittedWidth * (layer.x - 0.5f), y = fittedHeight * (layer.y - 0.5f))
                                .padding(horizontal = 10.dp)
                                .pointerInput(layer.id, layer.text) {
                                    detectTapGestures { tap ->
                                        selectedLayerId = layer.id
                                        val index = textLayout?.getOffsetForPosition(tap)
                                        if (index != null && index in layer.text.indices) selectedCharacterIndex = index
                                    }
                                }
                                ,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = if (layer.shadow) androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black,
                                    offset = androidx.compose.ui.geometry.Offset(2f, 3f),
                                    blurRadius = 5f
                                ) else null
                            )
                        )
                    }
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1.08f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text Layers", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val id = nextLayerId++
                    layers.add(GalleryTextLayer(id = id, text = "New Text", y = 0.55f))
                    selectedLayerId = id
                }) { Text("+ Add Text", color = Color(0xFF8CB9FF)) }
                TextButton(
                    enabled = layers.size > 1,
                    onClick = {
                        layers.removeAll { it.id == selectedLayerId }
                        selectedLayerId = layers.first().id
                    }
                ) { Text("Delete", color = Color(0xFFFF8A8A)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                layers.forEach { layer ->
                    FilterChip(
                        selected = layer.id == selectedLayerId,
                        onClick = { selectedLayerId = layer.id },
                        label = { Text(layer.text.ifBlank { "Text" }.take(10), maxLines = 1) }
                    )
                }
            }
            OutlinedTextField(
                value = current.text,
                onValueChange = { value ->
                    val oldText = current.text
                    var changedIndex = 0
                    while (changedIndex < oldText.length && changedIndex < value.length &&
                        oldText[changedIndex] == value[changedIndex]) {
                        changedIndex++
                    }
                    updateLayer { it.copy(text = value) }
                    val nextLetter = value.indices.firstOrNull {
                        it >= changedIndex && !value[it].isWhitespace()
                    } ?: value.indices.lastOrNull { !value[it].isWhitespace() }
                    selectedCharacterIndex = nextLetter ?: 0
                },
                label = { Text("Write your text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3
            )
            Text("Text Color", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (current.colorMode == "Letters" && current.text.isNotEmpty()) {
                Text("Choose a letter, then assign its color", color = Color.Gray, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    current.text.indices.filter { !current.text[it].isWhitespace() }.forEach { charIndex ->
                        FilterChip(
                            selected = selectedCharacterIndex == charIndex,
                            onClick = { selectedCharacterIndex = charIndex },
                            label = { Text(current.text[charIndex].toString()) }
                        )
                    }
                }
            }
            if (current.colorMode == "Lines") {
                Text("Choose a line, then assign its color", color = Color.Gray, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    current.text.split("\\n").indices.forEach { lineIndex ->
                        FilterChip(
                            selected = selectedLineIndex == lineIndex,
                            onClick = { selectedLineIndex = lineIndex },
                            label = { Text("Line ${lineIndex + 1}") }
                        )
                    }
                }
            }
            // Keep every color swatch on one horizontally scrollable row.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                galleryTextPalette.forEach { color ->
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(50))
                            .background(Color(color))
                            .clickable {
                                when (current.colorMode) {
                                    "Letters" -> {
                                        if (selectedCharacterIndex in current.text.indices) {
                                            updateLayer { it.copy(letterColors = it.letterColors + (selectedCharacterIndex to color)) }
                                        }
                                    }
                                    "Lines" -> {
                                        if (selectedLineIndex in current.text.split("\\n").indices) {
                                            updateLayer { it.copy(lineColors = it.lineColors + (selectedLineIndex to color)) }
                                        }
                                    }
                                    else -> updateLayer { it.copy(color = color) }
                                }
                            }
                    )
                }
            }
            Text("Color Filter", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Single", "Letters", "Lines", "Gradient").forEach { mode ->
                    FilterChip(
                        selected = current.colorMode == mode,
                        onClick = { updateLayer { it.copy(colorMode = mode) } },
                        label = { Text(mode) }
                    )
                }
            }
            Text("Use two fingers to resize text; drag with one finger to move", color = Color.Gray, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = current.bold, onClick = { updateLayer { it.copy(bold = !it.bold) } }, label = { Text("Bold") })
                FilterChip(selected = current.italic, onClick = { updateLayer { it.copy(italic = !it.italic) } }, label = { Text("Italic") })
                FilterChip(selected = current.shadow, onClick = { updateLayer { it.copy(shadow = !it.shadow) } }, label = { Text("Shadow") })
                FilterChip(selected = current.outline, onClick = { updateLayer { it.copy(outline = !it.outline) } }, label = { Text("Outline") })
            }
            Text("Drag text on the preview to place it anywhere", color = Color.Gray, fontSize = 11.sp)
            if (media.isVideo) {
                val timelineDuration = durationMs.coerceAtLeast(1000L)
                val clipStart = current.startMs.coerceIn(0L, timelineDuration)
                val clipEnd = current.endMs.coerceAtMost(timelineDuration).coerceAtLeast(clipStart)
                Text("Text timing", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Drag the clip to move it; drag either edge to trim", color = Color.Gray, fontSize = 11.sp)
                Canvas(
                    Modifier.fillMaxWidth().height(58.dp)
                        .pointerInput(current.id, timelineDuration) {
                            var mode = "move"
                            var initialStart = 0L
                            var initialEnd = 0L
                            var accumulated = 0f
                            detectDragGestures(
                                onDragStart = { point ->
                                    val leftPx = size.width * (clipStart.toFloat() / timelineDuration)
                                    val rightPx = size.width * (clipEnd.toFloat() / timelineDuration)
                                    mode = when {
                                        kotlin.math.abs(point.x - leftPx) <= 28.dp.toPx() -> "left"
                                        kotlin.math.abs(point.x - rightPx) <= 28.dp.toPx() -> "right"
                                        else -> "move"
                                    }
                                    initialStart = clipStart
                                    initialEnd = clipEnd
                                    accumulated = 0f
                                    selectedLayerId = current.id
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    accumulated += amount.x
                                    val delta = (accumulated / size.width * timelineDuration).toLong()
                                    when (mode) {
                                        "left" -> updateLayer { it.copy(startMs = (initialStart + delta).coerceIn(0L, initialEnd)) }
                                        "right" -> updateLayer { it.copy(endMs = (initialEnd + delta).coerceIn(initialStart, timelineDuration)) }
                                        else -> {
                                            val length = initialEnd - initialStart
                                            val newStart = (initialStart + delta).coerceIn(0L, timelineDuration - length)
                                            updateLayer { it.copy(startMs = newStart, endMs = newStart + length) }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val y = size.height / 2f
                    val left = size.width * (clipStart.toFloat() / timelineDuration)
                    val right = size.width * (clipEnd.toFloat() / timelineDuration)
                    drawLine(Color(0xFF555A64), Offset(0f, y), Offset(size.width, y), strokeWidth = 9.dp.toPx())
                    drawRoundRect(Color(0xFFDFAC42), topLeft = Offset(left, y - 9.dp.toPx()), size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(4f), 18.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                    drawRoundRect(Color.White, topLeft = Offset(left - 5.dp.toPx(), y - 16.dp.toPx()), size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 32.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawRoundRect(Color.White, topLeft = Offset(right - 5.dp.toPx(), y - 16.dp.toPx()), size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 32.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    val playX = size.width * (previewPositionMs.coerceIn(0L, timelineDuration).toFloat() / timelineDuration)
                    drawLine(Color(0xFF55C8FF), Offset(playX, 0f), Offset(playX, size.height), strokeWidth = 2.dp.toPx())
                }
                Text("${clipStart / 1000}s  —  ${clipEnd / 1000}s   •   Video ${timelineDuration / 1000}s", color = Color.Gray, fontSize = 11.sp)
            }
            Text("Font Family", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("sans-serif", "serif", "monospace").forEach { family ->
                    FilterChip(
                        selected = current.fontFamily == family,
                        onClick = { updateLayer { it.copy(fontFamily = family) } },
                        label = { Text(family.substringBefore('-').ifBlank { "Sans" }) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(
    uri: Uri,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    showHeader: Boolean = true,
    showActions: Boolean = true,
    onIndexChange: (Int) -> Unit = {},
    onEditClick: (() -> Unit)? = null,
    onCropClick: (() -> Unit)? = null,
    onSlideshowClick: (() -> Unit)? = null,
    onTextClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showDetailsDialog by remember(uri) { mutableStateOf(false) }
    var detailsText by remember(uri) { mutableStateOf("Loading details...") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        val decoded = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                null
            }
        }
        if (decoded != null) bitmap = decoded
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val viewWidthPx = with(density) { maxWidth.toPx() }
        val viewHeightPx = with(density) { maxHeight.toPx() }
        val currentBitmap = bitmap

        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = "Full photo",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Gestures are handled by the full-screen container, not the transformed Image.
        // This keeps touch coordinates stable while the image itself is zoomed and moved.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(uri, viewWidthPx, viewHeightPx, index, total, currentBitmap) {
                    var swipeDistance = 0f
                    var swipeHandled = false
                    var lastPanTime = 0L

                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val bitmapNow = currentBitmap ?: return@detectTransformGestures
                        val oldZoom = zoom
                        val newZoom = (oldZoom * zoomChange).coerceIn(1f, 6f)

                        if (oldZoom > 1.01f || newZoom > 1.01f) {
                            val fitScale = minOf(
                                viewWidthPx / bitmapNow.width.toFloat(),
                                viewHeightPx / bitmapNow.height.toFloat()
                            )
                            val fittedWidth = bitmapNow.width * fitScale
                            val fittedHeight = bitmapNow.height * fitScale
                            val maxX = ((fittedWidth * newZoom - viewWidthPx) / 2f)
                                .coerceAtLeast(0f)
                            val maxY = ((fittedHeight * newZoom - viewHeightPx) / 2f)
                                .coerceAtLeast(0f)

                            zoom = newZoom
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            swipeDistance = 0f
                            swipeHandled = false
                            lastPanTime = 0L
                        } else {
                            zoom = 1f
                            offsetX = 0f
                            offsetY = 0f

                            if (!swipeHandled) {
                                swipeDistance += pan.x

                                // Fast flicks should advance with a short movement;
                                // deliberate slow swipes still use a comfortable distance.
                                val now = android.os.SystemClock.uptimeMillis()
                                val elapsed = if (lastPanTime == 0L) 16L
                                    else (now - lastPanTime).coerceAtLeast(1L)
                                lastPanTime = now
                                val velocityX = pan.x * 1000f / elapsed
                                val fastFlickDistance = 12.dp.toPx()
                                val normalSwipeDistance = 28.dp.toPx()
                                val isFastLeft = velocityX < -950f &&
                                    swipeDistance <= -fastFlickDistance
                                val isFastRight = velocityX > 950f &&
                                    swipeDistance >= fastFlickDistance

                                if ((isFastLeft || swipeDistance <= -normalSwipeDistance) &&
                                    index < total - 1
                                ) {
                                    swipeHandled = true
                                    onIndexChange(index + 1)
                                } else if ((isFastRight || swipeDistance >= normalSwipeDistance) &&
                                    index > 0
                                ) {
                                    swipeHandled = true
                                    onIndexChange(index - 1)
                                }
                            }
                        }
                    }
                }
                .pointerInput(uri) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoom > 1f) {
                                zoom = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                zoom = 2.5f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    )
                }
        )

        if (showHeader) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    "Photo ${index + 1} / $total",
                    color = Color.White,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (showHeader && showActions) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xEE17181D))
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onEditClick != null) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onEditClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✎", color = Color.White, fontSize = 22.sp)
                        Text("Edit", color = Color.White, fontSize = 11.sp)
                    }
                }
                if (onSlideshowClick != null) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onSlideshowClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("▶", color = Color.White, fontSize = 22.sp)
                        Text("Slideshow", color = Color.White, fontSize = 11.sp)
                    }
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share photo")
                                )
                            } catch (_: Exception) {
                                Toast.makeText(context, "Unable to share photo", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("↗", color = Color.White, fontSize = 22.sp)
                    Text("Share", color = Color.White, fontSize = 11.sp)
                }
                if (onCropClick != null) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onCropClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⌗", color = Color.White, fontSize = 22.sp)
                        Text("Crop", color = Color.White, fontSize = 11.sp)
                    }
                }
                if (onTextClick != null) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onTextClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("T", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Text", color = Color.White, fontSize = 11.sp)
                    }
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            try {
                                val wallpaperIntent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    putExtra("mimeType", "image/*")
                                }
                                context.startActivity(Intent.createChooser(wallpaperIntent, "Set as wallpaper"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Wallpaper option is not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("▧", color = Color.White, fontSize = 22.sp)
                    Text("Wallpaper", color = Color.White, fontSize = 11.sp)
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            detailsText = try {
                                val columns = arrayOf(
                                    MediaStore.MediaColumns.DISPLAY_NAME,
                                    MediaStore.MediaColumns.SIZE,
                                    MediaStore.MediaColumns.MIME_TYPE,
                                    MediaStore.MediaColumns.WIDTH,
                                    MediaStore.MediaColumns.HEIGHT,
                                    MediaStore.MediaColumns.DATE_ADDED,
                                    MediaStore.MediaColumns.RELATIVE_PATH
                                )
                                var name = "Unknown"
                                var size = "Unknown"
                                var mime = "Unknown"
                                var width = "Unknown"
                                var height = "Unknown"
                                var date = "Unknown"
                                var path = "Not available"
                                context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        fun value(column: String): String? {
                                            val i = cursor.getColumnIndex(column)
                                            return if (i >= 0 && !cursor.isNull(i)) cursor.getString(i) else null
                                        }
                                        name = value(MediaStore.MediaColumns.DISPLAY_NAME) ?: name
                                        size = value(MediaStore.MediaColumns.SIZE)?.toLongOrNull()?.let {
                                            android.text.format.Formatter.formatShortFileSize(context, it)
                                        } ?: size
                                        mime = value(MediaStore.MediaColumns.MIME_TYPE) ?: mime
                                        width = value(MediaStore.MediaColumns.WIDTH) ?: width
                                        height = value(MediaStore.MediaColumns.HEIGHT) ?: height
                                        date = value(MediaStore.MediaColumns.DATE_ADDED)?.toLongOrNull()?.let {
                                            java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())
                                                .format(java.util.Date(it * 1000L))
                                        } ?: date
                                        path = value(MediaStore.MediaColumns.RELATIVE_PATH) ?: path
                                    }
                                }
                                "Name: $name\nSize: $size\nType: $mime\nResolution: $width × $height\nDate added: $date\nFolder: $path\nURI: $uri"
                            } catch (_: Exception) {
                                "Unable to load file details."
                            }
                            showDetailsDialog = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("ⓘ", color = Color.White, fontSize = 22.sp)
                    Text("Details", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }

    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text("Photo Details") },
            text = {
                Text(
                    detailsText,
                    modifier = Modifier.heightIn(max = 360.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}


@Composable
private fun PhotoCropScreen(
    uri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var cropLeft by remember(uri) { mutableFloatStateOf(0.10f) }
    var cropTop by remember(uri) { mutableFloatStateOf(0.10f) }
    var cropRight by remember(uri) { mutableFloatStateOf(0.90f) }
    var cropBottom by remember(uri) { mutableFloatStateOf(0.90f) }
    var saving by remember { mutableStateOf(false) }
    var selectedRatio by remember { mutableStateOf("Free") }

    LaunchedEffect(uri) {
        sourceBitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
        }
    }

    fun setRatio(label: String, ratio: Float?) {
        selectedRatio = label
        val bitmap = sourceBitmap ?: return
        val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val maxW = 0.86f
        val maxH = 0.86f
        val w: Float
        val h: Float
        if (ratio == null) {
            w = maxW
            h = maxH
        } else if (ratio >= imageRatio) {
            w = maxW
            h = (w * imageRatio / ratio).coerceAtMost(maxH)
        } else {
            h = maxH
            w = (h * ratio / imageRatio).coerceAtMost(maxW)
        }
        cropLeft = (1f - w) / 2f
        cropTop = (1f - h) / 2f
        cropRight = cropLeft + w
        cropBottom = cropTop + h
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(16.dp))
            Text("Crop Photo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                if (saving) "Saving..." else "Save Copy",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (saving) Color.DarkGray else Color(0xFF6550A4))
                    .clickable(enabled = !saving && sourceBitmap != null) {
                        val bitmap = sourceBitmap ?: return@clickable
                        val left = (cropLeft * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val top = (cropTop * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                        val right = (cropRight * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                        val bottom = (cropBottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                        saving = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                try {
                                    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, "GalleryK_Crop_${System.currentTimeMillis()}.jpg")
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery K")
                                            put(MediaStore.Images.Media.IS_PENDING, 1)
                                        }
                                    }
                                    val outputUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (outputUri == null) false else {
                                        val written = context.contentResolver.openOutputStream(outputUri)?.use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
                                        if (written && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                                            context.contentResolver.update(outputUri, ready, null, null)
                                        }
                                        if (!written) context.contentResolver.delete(outputUri, null, null)
                                        written
                                    }
                                } catch (_: Exception) { false }
                            }
                            saving = false
                            if (saved) onSaved() else Toast.makeText(context, "Could not save cropped photo", Toast.LENGTH_LONG).show()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }

        val bitmap = sourceBitmap
        if (bitmap == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / bitmap.width.toFloat(), boxH / bitmap.height.toFloat())
                val drawnW = bitmap.width * scale
                val drawnH = bitmap.height * scale
                val startX = (boxW - drawnW) / 2f
                val startY = (boxH - drawnH) / 2f
                val latestLeft = rememberUpdatedState(cropLeft)
                val latestTop = rememberUpdatedState(cropTop)
                val latestRight = rememberUpdatedState(cropRight)
                val latestBottom = rememberUpdatedState(cropBottom)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(bitmap) {
                        // Free crop: corners resize both axes, edge handles resize one
                        // axis, and dragging inside the frame moves the whole selection.
                        var dragMode = 0 // 0 = move, 1-4 = corners, 5-8 = edges
                        detectDragGestures(
                            onDragStart = { start ->
                                val l = startX + latestLeft.value * drawnW
                                val t = startY + latestTop.value * drawnH
                                val r = startX + latestRight.value * drawnW
                                val b = startY + latestBottom.value * drawnH
                                val midX = (l + r) / 2f
                                val midY = (t + b) / 2f
                                val handleRadius = 38.dp.toPx()
                                dragMode = when {
                                    kotlin.math.hypot(start.x - l, start.y - t) <= handleRadius -> 1
                                    kotlin.math.hypot(start.x - r, start.y - t) <= handleRadius -> 2
                                    kotlin.math.hypot(start.x - l, start.y - b) <= handleRadius -> 3
                                    kotlin.math.hypot(start.x - r, start.y - b) <= handleRadius -> 4
                                    kotlin.math.hypot(start.x - l, start.y - midY) <= handleRadius -> 5
                                    kotlin.math.hypot(start.x - r, start.y - midY) <= handleRadius -> 6
                                    kotlin.math.hypot(start.x - midX, start.y - t) <= handleRadius -> 7
                                    kotlin.math.hypot(start.x - midX, start.y - b) <= handleRadius -> 8
                                    else -> 0
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / drawnW
                                val dy = dragAmount.y / drawnH
                                var l = latestLeft.value
                                var t = latestTop.value
                                var r = latestRight.value
                                var b = latestBottom.value
                                when (dragMode) {
                                    1 -> { l = (l + dx).coerceIn(0f, r - 0.01f); t = (t + dy).coerceIn(0f, b - 0.01f) }
                                    2 -> { r = (r + dx).coerceIn(l + 0.01f, 1f); t = (t + dy).coerceIn(0f, b - 0.01f) }
                                    3 -> { l = (l + dx).coerceIn(0f, r - 0.01f); b = (b + dy).coerceIn(t + 0.01f, 1f) }
                                    4 -> { r = (r + dx).coerceIn(l + 0.01f, 1f); b = (b + dy).coerceIn(t + 0.01f, 1f) }
                                    5 -> l = (l + dx).coerceIn(0f, r - 0.01f)
                                    6 -> r = (r + dx).coerceIn(l + 0.01f, 1f)
                                    7 -> t = (t + dy).coerceIn(0f, b - 0.01f)
                                    8 -> b = (b + dy).coerceIn(t + 0.01f, 1f)
                                    else -> {
                                        val width = r - l
                                        val height = b - t
                                        l = (l + dx).coerceIn(0f, 1f - width)
                                        t = (t + dy).coerceIn(0f, 1f - height)
                                        r = l + width
                                        b = t + height
                                    }
                                }
                                cropLeft = l
                                cropTop = t
                                cropRight = r
                                cropBottom = b
                            }
                        )
                    }
                ) {
                    drawImage(
                        bitmap.asImageBitmap(),
                        dstOffset = IntOffset(startX.toInt(), startY.toInt()),
                        dstSize = IntSize(drawnW.toInt(), drawnH.toInt())
                    )
                    val l = startX + cropLeft * drawnW
                    val t = startY + cropTop * drawnH
                    val r = startX + cropRight * drawnW
                    val b = startY + cropBottom * drawnH
                    val shade = Color(0x99000000)
                    drawRect(shade, Offset(startX, startY), androidx.compose.ui.geometry.Size(drawnW, t - startY))
                    drawRect(shade, Offset(startX, b), androidx.compose.ui.geometry.Size(drawnW, startY + drawnH - b))
                    drawRect(shade, Offset(startX, t), androidx.compose.ui.geometry.Size(l - startX, b - t))
                    drawRect(shade, Offset(r, t), androidx.compose.ui.geometry.Size(startX + drawnW - r, b - t))
                    drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                    val handle = 11.dp.toPx()
                    val midX = (l + r) / 2f
                    val midY = (t + b) / 2f
                    // Eight touch handles make free-form adjustment easier on a phone.
                    listOf(
                        Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b),
                        Offset(l, midY), Offset(r, midY), Offset(midX, t), Offset(midX, b)
                    ).forEach { point ->
                        drawCircle(Color.White, handle / 2f, point)
                        drawCircle(Color(0xFF6550A4), handle / 4f, point)
                    }
                }
            }
            Text(
                "Free crop: drag any handle to resize, or drag inside to move",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Free" to null, "1:1" to 1f, "4:3" to (4f / 3f), "16:9" to (16f / 9f), "9:16" to (9f / 16f)).forEach { (label, ratio) ->
                OutlinedButton(
                    onClick = { setRatio(label, ratio) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedRatio == label) Color(0xFF8B70D6) else Color.Gray)
                ) { Text(label) }
            }
        }
    }
}


@Composable
private fun PhotoEditorScreen(
    uri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var originalBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var selectedFilter by remember(uri) { mutableIntStateOf(0) }
    var intensity by remember(uri) { mutableFloatStateOf(100f) }
    var saving by remember { mutableStateOf(false) }

    val filters = listOf(
        "Original", "Cinematic", "Golden", "Vintage", "Noir", "Dreamy",
        "Warm", "Cool", "Vivid", "Soft", "Moody", "Fade",
        "Retro", "Portrait", "Glow", "Classic", "Autumn", "Ocean",
        "Drama", "Matte", "Rose", "Forest", "Silver", "Sunset"
    )

    LaunchedEffect(uri) {
        originalBitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) { null }
        }
    }

    var brightness by remember(uri) { mutableFloatStateOf(0f) }

    // Each filter has its own color, contrast and warmth profile.
    val filterProfiles = listOf(
        floatArrayOf(1.00f, 1.00f, 0f, 0f),
        floatArrayOf(1.22f, 1.12f, 3f, 0f),
        floatArrayOf(1.30f, 1.08f, 7f, 0f),
        floatArrayOf(0.72f, 0.92f, 5f, 0f),
        floatArrayOf(0.00f, 1.28f, 2f, 0f),
        floatArrayOf(0.82f, 1.08f, 10f, 0f),
        floatArrayOf(1.18f, 1.05f, 5f, 0f),
        floatArrayOf(0.86f, 1.08f, -4f, 0f),
        floatArrayOf(1.55f, 1.18f, 0f, 0f),
        floatArrayOf(0.68f, 0.90f, 8f, 0f),
        floatArrayOf(0.62f, 1.24f, -5f, 0f),
        floatArrayOf(0.76f, 0.82f, 10f, 0f),
        floatArrayOf(0.84f, 1.10f, 6f, 0f),
        floatArrayOf(1.08f, 1.04f, 3f, 0f),
        floatArrayOf(1.32f, 1.12f, 12f, 0f),
        floatArrayOf(1.00f, 1.10f, 0f, 0f),
        floatArrayOf(1.22f, 1.10f, 8f, 0f),
        floatArrayOf(0.92f, 1.08f, -3f, 0f),
        floatArrayOf(1.42f, 1.28f, 1f, 0f),
        floatArrayOf(0.66f, 0.84f, 8f, 0f),
        floatArrayOf(1.16f, 1.04f, 5f, 0f),
        floatArrayOf(1.12f, 1.08f, 2f, 0f),
        floatArrayOf(0.12f, 1.18f, 0f, 0f),
        floatArrayOf(1.35f, 1.12f, 9f, 0f)
    )

    fun createEditMatrix(): android.graphics.ColorMatrix {
        val profile = filterProfiles[selectedFilter]
        val amount = intensity / 100f
        val saturation = 1f + (profile[0] - 1f) * amount
        val contrast = 1f + (profile[1] - 1f) * amount
        val filterBrightness = profile[2] * amount + brightness
        val matrix = android.graphics.ColorMatrix()
        matrix.setSaturation(saturation)
        val contrastMatrix = android.graphics.ColorMatrix().apply {
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, 128f * (1f - contrast),
                0f, contrast, 0f, 0f, 128f * (1f - contrast),
                0f, 0f, contrast, 0f, 128f * (1f - contrast),
                0f, 0f, 0f, 1f, 0f
            ))
        }
        matrix.postConcat(contrastMatrix)
        val brightnessMatrix = android.graphics.ColorMatrix().apply {
            set(floatArrayOf(
                1f, 0f, 0f, 0f, filterBrightness,
                0f, 1f, 0f, 0f, filterBrightness,
                0f, 0f, 1f, 0f, filterBrightness,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        matrix.postConcat(brightnessMatrix)
        return matrix
    }

    val composeColorMatrix = androidx.compose.ui.graphics.ColorMatrix(createEditMatrix().array)

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101114))
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(16.dp))
            Text("Photo Editor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                if (saving) "Saving..." else "Save Copy",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (saving) Color.DarkGray else Color(0xFF6550A4))
                    .clickable(enabled = !saving && originalBitmap != null) {
                        val source = originalBitmap
                        if (source != null) {
                            val editMatrix = createEditMatrix()
                            saving = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    try {
                                        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(output)
                                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                        paint.colorFilter = android.graphics.ColorMatrixColorFilter(editMatrix)
                                        canvas.drawBitmap(source, 0f, 0f, paint)
                                        val values = ContentValues().apply {
                                            put(MediaStore.Images.Media.DISPLAY_NAME, "GalleryK_Edit_${System.currentTimeMillis()}.jpg")
                                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gallery K")
                                            }
                                        }
                                        val outputUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                        val written = outputUri?.let { target ->
                                            context.contentResolver.openOutputStream(target)?.use { stream ->
                                                output.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                                            } ?: false
                                        } ?: false
                                        output.recycle()
                                        written
                                    } catch (_: Exception) { false }
                                }
                                saving = false
                                if (saved) onSaved()
                                else Toast.makeText(context, "Could not save edited photo", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val image = originalBitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Edited photo preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(composeColorMatrix)
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Text("FILTERS", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters.size) { filterIndex ->
                Column(
                    Modifier.width(76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selectedFilter == filterIndex) Color(0xFF6550A4) else Color(0xFF25262B))
                        .clickable { selectedFilter = filterIndex }
                        .padding(vertical = 10.dp, horizontal = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(filters[filterIndex], color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        Text("Intensity  ${intensity.toInt()}%", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp))
        Slider(
            value = intensity,
            onValueChange = { intensity = it },
            valueRange = 0f..100f,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Text(
            "Brightness  ${brightness.toInt()}",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            valueRange = -100f..100f,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun VideoPlayer(
uri: Uri,
onBack: () -> Unit,
enableSeekGestures: Boolean = true,
onTextClick: (() -> Unit)? = null
) {
val context = LocalContext.current
val activity = remember(context) {
var current = context
while (
current !is Activity &&
current is android.content.ContextWrapper
) {
current = current.baseContext
}
current as? Activity
}
val density = LocalDensity.current.density

val exoPlayer = remember(uri) {  
    ExoPlayer.Builder(context).build().apply {  
        setMediaItem(MediaItem.fromUri(uri))  
        prepare()  
        playWhenReady = true  
        volume = 1f  
    }  
}  

var indicatorType by remember { mutableStateOf("") }  
var indicatorLevel by remember { mutableFloatStateOf(0f) }  
var indicatorVisible by remember { mutableStateOf(false) }  
var indicatorResetKey by remember { mutableIntStateOf(0) }  

var controlsVisible by remember { mutableStateOf(true) }  
var currentPosition by remember { mutableLongStateOf(0L) }  
var duration by remember { mutableLongStateOf(0L) }  
var isScrubbing by remember { mutableStateOf(false) }  
var sliderPosition by remember { mutableFloatStateOf(0f) }  
var isPlaying by remember { mutableStateOf(true) }  
var videoZoom by remember(uri) { mutableFloatStateOf(1f) }  

DisposableEffect(exoPlayer) {  
    val listener = object : Player.Listener {  
        override fun onIsPlayingChanged(playing: Boolean) {  
            isPlaying = playing  
            controlsVisible = true  
        }  

        override fun onPlaybackStateChanged(state: Int) {  
            duration = exoPlayer.duration.coerceAtLeast(0L)  
        }  
    }  

    exoPlayer.addListener(listener)  

    onDispose {  
        exoPlayer.removeListener(listener)  
    }  
}  

LaunchedEffect(exoPlayer, isScrubbing) {  
    while (true) {  
        duration = exoPlayer.duration.coerceAtLeast(0L)  

        if (!isScrubbing) {  
            currentPosition =  
                exoPlayer.currentPosition.coerceAtLeast(0L)  
            sliderPosition = currentPosition.toFloat()  
        }  

        delay(200)  
    }  
}  

LaunchedEffect(controlsVisible, isPlaying, isScrubbing) {  
    if (controlsVisible && isPlaying && !isScrubbing) {  
        delay(3000)  
        controlsVisible = false  
    }  
}  

LaunchedEffect(indicatorResetKey) {  
    if (indicatorVisible) {  
        delay(1500)  
        indicatorVisible = false  
    }  
}  

DisposableEffect(activity, exoPlayer) {  
    val window = activity?.window  
    val originalBrightness =  
        window?.attributes?.screenBrightness ?: -1f  
    val originalOrientation = activity?.requestedOrientation  

    val controller = window?.let {  
        WindowInsetsControllerCompat(it, it.decorView)  
    }  

    controller?.systemBarsBehavior =  
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE  

    controller?.hide(WindowInsetsCompat.Type.systemBars())  

    onDispose {  
        window?.let {  
            val params = it.attributes  
            params.screenBrightness = originalBrightness  
            it.attributes = params  
        }  

        controller?.show(WindowInsetsCompat.Type.systemBars())  
        originalOrientation?.let { activity?.requestedOrientation = it }  
        exoPlayer.release()  
    }  
}  

BackHandler { onBack() }  

fun formatTime(ms: Long): String {  
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)  
    val hours = totalSeconds / 3600  
    val minutes = (totalSeconds % 3600) / 60  
    val seconds = totalSeconds % 60  

    return if (hours > 0) {  
        String.format("%d:%02d:%02d", hours, minutes, seconds)  
    } else {  
        String.format("%02d:%02d", minutes, seconds)  
    }  
}  

Box(  
    modifier = Modifier  
        .fillMaxSize()  
        .background(Color.Black)  
) {  
    AndroidView(  
        factory = { viewContext ->  
            PlayerView(viewContext).apply {  
                player = exoPlayer  
                useController = false  
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT  
                setShowBuffering(  
                    PlayerView.SHOW_BUFFERING_WHEN_PLAYING  
                )  
            }  
        },  
        update = { playerView ->  
            playerView.player = exoPlayer  
            playerView.useController = false  
            playerView.resizeMode =  
                AspectRatioFrameLayout.RESIZE_MODE_FIT  
        },  
        modifier = Modifier.fillMaxSize().graphicsLayer {  
            scaleX = videoZoom  
            scaleY = videoZoom  
        }  
    )  

    Box(  
        modifier = Modifier  
            .fillMaxSize()  
            .pointerInput(exoPlayer) {  
                detectTransformGestures { _, _, zoomChange, _ ->  
                    videoZoom = (videoZoom * zoomChange).coerceIn(1f, 10f)  
                }  
            }  
            .pointerInput(exoPlayer) {  
                detectTapGestures(  
                    onTap = {  
                        controlsVisible = !controlsVisible  
                    },  
                    onDoubleTap = {  
                        if (exoPlayer.isPlaying) {  
                            exoPlayer.pause()  
                        } else {  
                            exoPlayer.play()  
                        }  
                        controlsVisible = true  
                    }  
                )  
            }  
            .pointerInput(exoPlayer, density, enableSeekGestures) {
                if (enableSeekGestures) {
  
                var startPosition = 0L  
                var dragDistancePx = 0f  

                detectHorizontalDragGestures(  
                    onDragStart = {  
                        startPosition = exoPlayer.currentPosition  
                        dragDistancePx = 0f  
                        controlsVisible = true  
                    },  
                    onHorizontalDrag = { change, dragAmount ->  
                        change.consume()  
                        dragDistancePx += dragAmount  

                        val dragDp = dragDistancePx / density  
                        val timeChange = (dragDp * 100L).toLong()  

                        var targetPosition =  
                            (startPosition + timeChange)  
                                .coerceAtLeast(0L)  

                        val videoDuration = exoPlayer.duration  
                        if (videoDuration > 0L) {  
                            targetPosition =  
                                targetPosition.coerceAtMost(videoDuration)  
                        }  

                        exoPlayer.seekTo(targetPosition)  
                        currentPosition = targetPosition  
                        sliderPosition = targetPosition.toFloat()  
                    }  
                )  
            
                }
            }
            .pointerInput(exoPlayer, density, activity) {  
                var brightnessSide = false  
                var startBrightness = 0.5f  
                var startVolume = 1f  
                var dragDistance = 0f  

                detectVerticalDragGestures(  
                    onDragStart = { offset ->  
                        brightnessSide = offset.x < size.width / 2f  
                        dragDistance = 0f  

                        if (brightnessSide) {  
                            val currentBrightness =  
                                activity?.window?.attributes  
                                    ?.screenBrightness ?: -1f  

                            startBrightness =  
                                if (currentBrightness < 0f) {  
                                    0.5f  
                                } else {  
                                    currentBrightness  
                                }  

                            indicatorType = "brightness"  
                            indicatorLevel = startBrightness  
                        } else {  
                            startVolume =  
                                exoPlayer.volume.coerceIn(0f, 1f)  

                            indicatorType = "volume"  
                            indicatorLevel = startVolume  
                        }  

                        indicatorVisible = true  
                        indicatorResetKey++  
                    },  
                    onVerticalDrag = { change, dragAmount ->  
                        change.consume()  
                        dragDistance += dragAmount  

                        val adjustment =  
                            dragDistance / (size.height * 0.60f)  

                        if (brightnessSide) {  
                            val newBrightness =  
                                (startBrightness - adjustment)  
                                    .coerceIn(0.01f, 1f)  

                            activity?.window?.let { window ->  
                                val params = window.attributes  
                                params.screenBrightness = newBrightness  
                                window.attributes = params  
                            }  

                            indicatorLevel = newBrightness  
                        } else {  
                            val newVolume =  
                                (startVolume - adjustment)  
                                    .coerceIn(0f, 1f)  

                            exoPlayer.volume = newVolume  
                            indicatorLevel = newVolume  
                        }  

                        indicatorVisible = true  
                        indicatorResetKey++  
                    },  
                    onDragEnd = {  
                        indicatorVisible = true  
                        indicatorResetKey++  
                    }  
                )  
            }  
    )  

    if (controlsVisible) {  
        Box(Modifier.fillMaxSize()) {  
            if (onTextClick != null) {
                Text(
                    "T  Text",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 14.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x88000000))
                        .clickable { onTextClick() }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
            Text(  
                "⟳",  
                color = Color.White,  
                fontSize = 30.sp,  
                modifier = Modifier  
                    .align(Alignment.TopEnd)  
                    .padding(top = 20.dp, end = 18.dp)  
                    .clip(RoundedCornerShape(50))  
                    .background(Color(0x55000000))  
                    .clickable {  
                        val isLandscape = activity?.resources?.configuration?.orientation ==  
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE  
                        activity?.requestedOrientation = if (isLandscape) {  
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT  
                        } else {  
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE  
                        }  
                        controlsVisible = true  
                    }  
                    .padding(horizontal = 14.dp, vertical = 10.dp)  
            )  
            Text(  
                if (isPlaying) "Ⅱ" else "▶",  
                color = Color.White,  
                fontSize = 38.sp,  
                modifier = Modifier  
                    .align(Alignment.Center)  
                    .clip(RoundedCornerShape(50))  
                    .background(Color(0x55000000))  
                    .clickable {  
                        if (isPlaying) {  
                            exoPlayer.pause()  
                        } else {  
                            exoPlayer.play()  
                        }  
                        controlsVisible = true  
                    }  
                    .padding(18.dp)  
            )  

            Column(  
                modifier = Modifier  
                    .align(Alignment.BottomCenter)  
                    .fillMaxWidth()  
                    .background(Color(0x88000000))  
                    .padding(  
                        start = 14.dp,  
                        end = 14.dp,  
                        top = 8.dp,  
                        bottom = 12.dp  
                    )  
            ) {  
                Row(  
                    modifier = Modifier.fillMaxWidth(),  
                    verticalAlignment = Alignment.CenterVertically  
                ) {  
                    Text(  
                        formatTime(  
                            if (isScrubbing) {  
                                sliderPosition.toLong()  
                            } else {  
                                currentPosition  
                            }  
                        ),  
                        color = Color.White,  
                        fontSize = 11.sp  
                    )  

                    Spacer(Modifier.weight(1f))  

                    Text(  
                        formatTime(duration),  
                        color = Color.White,  
                        fontSize = 11.sp  
                    )  
                }  

                Slider(  
                    value = sliderPosition.coerceIn(  
                        0f,  
                        duration.coerceAtLeast(1L).toFloat()  
                    ),  
                    onValueChange = { value ->  
                        isScrubbing = true  
                        sliderPosition = value  
                        controlsVisible = true  
                    },  
                    onValueChangeFinished = {  
                        exoPlayer.seekTo(sliderPosition.toLong())  
                        currentPosition = sliderPosition.toLong()  
                        isScrubbing = false  
                    },  
                    valueRange =  
                        0f..duration.coerceAtLeast(1L).toFloat(),  
                    modifier = Modifier  
                        .fillMaxWidth()  
                        .height(30.dp),  
                    colors = SliderDefaults.colors(  
                        thumbColor = Color.White,  
                        activeTrackColor = Color.White,  
                        inactiveTrackColor = Color(0x66FFFFFF)  
                    )  
                )  
            }  
        }  
    }  

    if (indicatorVisible && indicatorType == "brightness") {  
        Column(  
            modifier = Modifier  
                .align(Alignment.CenterStart)  
                .padding(start = 18.dp),  
            horizontalAlignment = Alignment.CenterHorizontally,  
            verticalArrangement = Arrangement.spacedBy(7.dp)  
        ) {  
            Text(  
                "☀",  
                color = Color.White,  
                fontSize = 18.sp  
            )  

            Box(  
                modifier = Modifier  
                    .width(5.dp)  
                    .height(68.dp)  
                    .clip(RoundedCornerShape(50))  
                    .background(Color(0x66FFFFFF))  
            ) {  
                Box(  
                    modifier = Modifier  
                        .align(Alignment.BottomCenter)  
                        .fillMaxWidth()  
                        .fillMaxHeight(  
                            indicatorLevel.coerceIn(0.01f, 1f)  
                        )  
                        .background(Color.White)  
                )  
            }  

            Text(  
                "${(indicatorLevel * 100).toInt()}%",  
                color = Color.White,  
                fontSize = 10.sp,  
                fontWeight = FontWeight.SemiBold  
            )  
        }  
    }  

    if (indicatorVisible && indicatorType == "volume") {  
        Column(  
            modifier = Modifier  
                .align(Alignment.CenterEnd)  
                .padding(end = 18.dp),  
            horizontalAlignment = Alignment.CenterHorizontally,  
            verticalArrangement = Arrangement.spacedBy(7.dp)  
        ) {  
            Text(  
                "◖))",  
                color = Color.White,  
                fontSize = 17.sp,  
                fontWeight = FontWeight.Bold  
            )  

            Box(  
                modifier = Modifier  
                    .width(5.dp)  
                    .height(68.dp)  
                    .clip(RoundedCornerShape(50))  
                    .background(Color(0x66FFFFFF))  
            ) {  
                Box(  
                    modifier = Modifier  
                        .align(Alignment.BottomCenter)  
                        .fillMaxWidth()  
                        .fillMaxHeight(  
                            indicatorLevel.coerceIn(0.01f, 1f)  
                        )  
                        .background(Color.White)  
                )  
            }  

            Text(  
                "${(indicatorLevel * 100).toInt()}%",  
                color = Color.White,  
                fontSize = 10.sp,  
                fontWeight = FontWeight.SemiBold  
            )  
        }  
    }  
}

}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PrivacyFolderScreen(
    onBack: () -> Unit,
    onOriginalsDeleted: () -> Unit,
    onChangePin: () -> Unit,
    onPickerStart: () -> Unit,
    onPickerEnd: () -> Unit,
    fingerprintEnabled: Boolean,
    onFingerprintEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedPhotos by remember { mutableStateOf(listOf<Uri>()) }
    var pickedVideos by remember { mutableStateOf(listOf<Uri>()) }
    var saving by remember { mutableStateOf(false) }
    var savedPhotoCount by remember { mutableIntStateOf(0) }
    var savedVideoCount by remember { mutableIntStateOf(0) }
    var savedFiles by remember { mutableStateOf(listOf<File>()) }
    var previewFiles by remember { mutableStateOf(listOf<File>()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var showDeletePrivateConfirm by remember { mutableStateOf(false) }
    var mediaFilter by remember { mutableStateOf("All") }
    var selectedPrivateFiles by remember { mutableStateOf(setOf<String>()) }
    var privateSelectionDragging by remember { mutableStateOf(false) }
    var privacyMenuExpanded by remember { mutableStateOf(false) }
    var showPrivacyPicker by remember { mutableStateOf(false) }
    var pickerMedia by remember { mutableStateOf(listOf<GalleryMedia>()) }
    var pickerAlbums by remember { mutableStateOf(listOf<PhotoAlbum>()) }
    var pickerTab by remember { mutableStateOf("Photos") }
    var pickerAlbumId by remember { mutableStateOf<String?>(null) }
    var pickerSelectedUris by remember { mutableStateOf(setOf<Uri>()) }

    BackHandler(enabled = previewFiles.isNotEmpty()) {
        previewFiles = emptyList()
        previewIndex = 0
    }

    val privatePhotosDir = remember { File(context.filesDir, "privacy_folder/photos") }
    val privateVideosDir = remember { File(context.filesDir, "privacy_folder/videos") }
    fun toMediaStoreUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            if (uri.authority == MediaStore.AUTHORITY) uri
            else MediaStore.getMediaUri(context, uri)
        } catch (_: Exception) {
            null
        }
    }

    fun refreshSavedFiles() {
        val photos = privatePhotosDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val videos = privateVideosDir.listFiles()?.filter { it.isFile } ?: emptyList()
        savedPhotoCount = photos.size
        savedVideoCount = videos.size
        savedFiles = (photos + videos).sortedByDescending { it.lastModified() }
    }

    suspend fun restorePrivateFile(file: File): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val isVideo = file.parentFile == privateVideosDir
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val mime = if (isVideo) {
            when (file.extension.lowercase()) {
                "3gp" -> "video/3gpp"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                else -> "video/mp4"
            }
        } else {
            when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
        }
        val folder = if (isVideo) "Movies/Gallery K Restored" else "Pictures/Gallery K Restored"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val newUri = resolver.insert(collection, values) ?: return@withContext false
        try {
            resolver.openOutputStream(newUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open output")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(newUri, ready, null, null)
            file.delete()
            true
        } catch (_: Exception) {
            try { resolver.delete(newUri, null, null) } catch (_: Exception) { }
            false
        }
    }

    LaunchedEffect(Unit) { refreshSavedFiles() }

    LaunchedEffect(showPrivacyPicker) {
        if (showPrivacyPicker) {
            try {
                val result = loadGallery(context)
                pickerMedia = result.first
                pickerAlbums = result.second
            } catch (e: Exception) {
                Toast.makeText(context, "Could not load Gallery media: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler(enabled = showPrivacyPicker) {
        if (pickerAlbumId != null) {
            pickerAlbumId = null
        } else {
            showPrivacyPicker = false
            pickerSelectedUris = emptySet()
            onPickerEnd()
        }
    }

    val deleteOriginalsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onOriginalsDeleted()
            Toast.makeText(context, "Original media removed from Gallery", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Private copies saved. Originals were kept in Gallery.", Toast.LENGTH_LONG).show()
        }
    }

    if (showPrivacyPicker) {
        val pickerAlbum = pickerAlbums.find { it.id == pickerAlbumId }
        val pickerVisibleMedia = when {
            pickerAlbum != null -> pickerAlbum.media
            pickerTab == "Videos" -> pickerMedia.filter { it.isVideo }
            pickerTab == "Albums" -> emptyList()
            else -> pickerMedia.filter { !it.isVideo }
        }
        val pickerGridState = rememberLazyGridState()
        val pickerScope = rememberCoroutineScope()
        val latestPickerSelection = rememberUpdatedState(pickerSelectedUris)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101114))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pickerAlbum != null) "‹  ${pickerAlbum.name}" else "‹  Gallery",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable {
                        if (pickerAlbumId != null) pickerAlbumId = null
                        else {
                            showPrivacyPicker = false
                            pickerSelectedUris = emptySet()
                            onPickerEnd()
                        }
                    }
                )
                TextButton(onClick = {
                    val visible = pickerVisibleMedia.map { it.uri }.toSet()
                    pickerSelectedUris = if (visible.isNotEmpty() && visible.all { it in pickerSelectedUris }) {
                        pickerSelectedUris - visible
                    } else pickerSelectedUris + visible
                }) {
                    Text("Select All", color = Color.White)
                }
            }

            if (pickerAlbum == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Photos", "Videos", "Albums").forEach { tab ->
                        val active = pickerTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) Color(0xFF6550A4) else Color(0xFF29262D))
                                .clickable { pickerTab = tab }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tab, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (pickerAlbum == null && pickerTab == "Albums") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(pickerAlbums, key = { it.id }) { album ->
                        AlbumCard(album) { pickerAlbumId = album.id }
                    }
                }
            } else if (pickerVisibleMedia.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No media found", color = Color.Gray)
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(pickerVisibleMedia) {
                            var lastX = 0f
                            var lastY = 0f
                            var edgeDirection = 0
                            var trackingPickerSelection = false
                            var dragSelectedUris = emptySet<Uri>()
                            var lastSelectedRow = -1
                            var accumulatedDy = 0f
                            var dragDirection = 1
                            var autoScrollJob: kotlinx.coroutines.Job? = null

                            // Match the main Gallery behavior: dragging across a row
                            // selects all three items in that row, including rows passed
                            // while the grid is auto-scrolling.
                            fun selectAt(y: Float) {
                                val visibleItems = pickerGridState.layoutInfo.visibleItemsInfo
                                val hit = visibleItems.firstOrNull { info ->
                                    y >= info.offset.y && y < info.offset.y + info.size.height
                                } ?: visibleItems.minByOrNull { info ->
                                    kotlin.math.abs((info.offset.y + info.size.height / 2f) - y)
                                } ?: return
                                val targetRow = hit.index / 3
                                val rows = if (lastSelectedRow < 0) {
                                    listOf(targetRow)
                                } else if (lastSelectedRow <= targetRow) {
                                    (lastSelectedRow..targetRow).toList()
                                } else {
                                    (lastSelectedRow downTo targetRow).toList()
                                }
                                val rowUris = rows.flatMap { row ->
                                    val start = row * 3
                                    (start until (start + 3).coerceAtMost(pickerVisibleMedia.size))
                                        .mapNotNull { pickerVisibleMedia.getOrNull(it)?.uri }
                                }.toSet()
                                val updated = if (dragDirection > 0) {
                                    dragSelectedUris + rowUris
                                } else {
                                    dragSelectedUris - rowUris
                                }
                                if (updated != dragSelectedUris) {
                                    dragSelectedUris = updated
                                    pickerSelectedUris = updated
                                }
                                lastSelectedRow = targetRow
                            }

                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    trackingPickerSelection = true
                                    if (trackingPickerSelection) {
                                        dragSelectedUris = latestPickerSelection.value
                                        lastX = start.x
                                        lastY = start.y
                                        edgeDirection = 0
                                        lastSelectedRow = -1
                                        accumulatedDy = 0f
                                        dragDirection = 1
                                        selectAt(start.y)
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (trackingPickerSelection) {
                                        change.consume()
                                        val pos = change.position
                                        val dy = pos.y - lastY
                                        accumulatedDy += dy
                                        if (accumulatedDy >= 10f) {
                                            dragDirection = 1
                                            accumulatedDy = 0f
                                        } else if (accumulatedDy <= -10f) {
                                            dragDirection = -1
                                            accumulatedDy = 0f
                                        }
                                        selectAt(pos.y)
                                        lastX = pos.x
                                        lastY = pos.y
                                        val edge = 110.dp.toPx()
                                        edgeDirection = when {
                                            pos.y <= edge -> -1
                                            pos.y >= size.height - edge -> 1
                                            else -> 0
                                        }
                                        if (edgeDirection != 0 && autoScrollJob?.isActive != true) {
                                            autoScrollJob = pickerScope.launch {
                                                while (edgeDirection != 0 && trackingPickerSelection) {
                                                    pickerGridState.scrollBy(edgeDirection * 9f)
                                                    selectAt(lastY)
                                                    kotlinx.coroutines.delay(16L)
                                                }
                                            }
                                        } else if (edgeDirection == 0) {
                                            autoScrollJob?.cancel()
                                            autoScrollJob = null
                                        }
                                    }
                                },
                                onDragEnd = {
                                    trackingPickerSelection = false
                                    edgeDirection = 0
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                },
                                onDragCancel = {
                                    trackingPickerSelection = false
                                    edgeDirection = 0
                                    autoScrollJob?.cancel()
                                    autoScrollJob = null
                                }
                            )
                        }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = pickerGridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(pickerVisibleMedia, key = { it.uri.toString() }) { media ->
                            MediaThumbnail(
                                media = media,
                                selected = media.uri in pickerSelectedUris,
                                onClick = {
                                    pickerSelectedUris = if (media.uri in pickerSelectedUris) {
                                        pickerSelectedUris - media.uri
                                    } else pickerSelectedUris + media.uri
                                },
                                onLongClick = null
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        showPrivacyPicker = false
                        pickerSelectedUris = emptySet()
                        onPickerEnd()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val selected = pickerMedia.filter { it.uri in pickerSelectedUris }
                        pickedPhotos = selected.filter { !it.isVideo }.map { it.uri }
                        pickedVideos = selected.filter { it.isVideo }.map { it.uri }
                        showPrivacyPicker = false
                        pickerSelectedUris = emptySet()
                        onPickerEnd()
                    },
                    enabled = pickerSelectedUris.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6550A4))
                ) { Text("Add Selected (${pickerSelectedUris.size})") }
            }
        }
        return
    }

    val openFile = previewFiles.getOrNull(previewIndex)
    if (openFile != null) {
        val uri = Uri.fromFile(openFile)
        if (openFile.parentFile == privateVideosDir) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(previewIndex, previewFiles.size) {
                        var dragTotal = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragTotal += dragAmount
                            },
                            onDragEnd = {
                                val threshold = 36.dp.toPx()
                                if (dragTotal < -threshold && previewIndex < previewFiles.lastIndex) {
                                    previewIndex++
                                } else if (dragTotal > threshold && previewIndex > 0) {
                                    previewIndex--
                                }
                            },
                            onDragCancel = { dragTotal = 0f }
                        )
                    }
            ) {
                VideoPlayer(uri = uri, onBack = { previewFiles = emptyList(); previewIndex = 0 }, enableSeekGestures = false)
                Text(
                    "Video ${previewIndex + 1} / ${previewFiles.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
                )
            }
        } else {
            PhotoViewer(
                uri = uri,
                index = previewIndex,
                total = previewFiles.size,
                onBack = { previewFiles = emptyList(); previewIndex = 0 },
                onIndexChange = { previewIndex = it }
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "‹  Back",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Privacy Folder", color = Color.White, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { privacyMenuExpanded = true }) {
                    Text("⋮", color = Color.White, fontSize = 26.sp)
                }
                DropdownMenu(expanded = privacyMenuExpanded, onDismissRequest = { privacyMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Fingerprint Unlock")
                                    Text(if (fingerprintEnabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = fingerprintEnabled, onCheckedChange = { enabled -> onFingerprintEnabledChange(enabled); privacyMenuExpanded = false })
                            }
                        },
                        onClick = { onFingerprintEnabledChange(!fingerprintEnabled); privacyMenuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Change PIN") },
                        onClick = {
                            privacyMenuExpanded = false
                            onChangePin()
                        }
                    )
                }
            }
        }
        Button(
            onClick = {
                pickerSelectedUris = emptySet()
                pickerTab = "Photos"
                pickerAlbumId = null
                showPrivacyPicker = true
                onPickerStart()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Photos & Videos (${pickedPhotos.size + pickedVideos.size})") }

        Text(
            text = "Selected: ${pickedPhotos.size} photos • ${pickedVideos.size} videos",
            color = Color.White,
            fontSize = 15.sp
        )

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    val photos = pickedPhotos.toList()
                    val videos = pickedVideos.toList()
                    val result = withContext(Dispatchers.IO) {
                        var photoSaved = 0
                        var videoSaved = 0
                        val copiedUris = mutableListOf<Uri>()
                        try {
                            privatePhotosDir.mkdirs()
                            privateVideosDir.mkdirs()
                            photos.forEach { uri ->
                                val ext = context.contentResolver.getType(uri)
                                    ?.substringAfter("/", "jpg")?.replace("[^A-Za-z0-9]".toRegex(), "")
                                    ?.take(8)?.ifBlank { "jpg" } ?: "jpg"
                                val target = File(privatePhotosDir, "photo_${System.currentTimeMillis()}_${photoSaved}.$ext")
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                }
                                if (target.exists() && target.length() > 0) {
                                    photoSaved++
                                    toMediaStoreUri(uri)?.let { copiedUris.add(it) }
                                } else target.delete()
                            }
                            videos.forEach { uri ->
                                val ext = context.contentResolver.getType(uri)
                                    ?.substringAfter("/", "mp4")?.replace("[^A-Za-z0-9]".toRegex(), "")
                                    ?.take(8)?.ifBlank { "mp4" } ?: "mp4"
                                val target = File(privateVideosDir, "video_${System.currentTimeMillis()}_${videoSaved}.$ext")
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                }
                                if (target.exists() && target.length() > 0) {
                                    videoSaved++
                                    toMediaStoreUri(uri)?.let { copiedUris.add(it) }
                                } else target.delete()
                            }
                        } catch (_: Exception) { }
                        Pair(photoSaved to videoSaved, copiedUris.toList())
                    }
                    refreshSavedFiles()
                    pickedPhotos = emptyList()
                    pickedVideos = emptyList()
                    saving = false
                    Toast.makeText(
                        context,
                        "Saved ${result.first.first} photos and ${result.first.second} videos privately",
                        Toast.LENGTH_LONG
                    ).show()

                    // Delete originals only after a private copy was successfully created,
                    // and only after the user confirms Android's system delete dialog.
                    val copiedSuccessfully = result.second.distinct()
                    if (copiedSuccessfully.isNotEmpty()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val deleteRequest = MediaStore.createDeleteRequest(
                                    context.contentResolver, copiedSuccessfully
                                )
                                deleteOriginalsLauncher.launch(
                                    IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                                )
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "Private copies saved, but Gallery removal could not be started.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Private copies saved. Android requires a separate delete permission on this version.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else if (result.first.first + result.first.second > 0) {
                        Toast.makeText(
                            context,
                            "Private copies saved, but Android could not map the selected files for Gallery deletion. Originals were kept.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            enabled = (pickedPhotos.isNotEmpty() || pickedVideos.isNotEmpty()) && !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Saving…" else "Save to Privacy Folder") }

        Text(
            text = "Saved privately: $savedPhotoCount photos • $savedVideoCount videos",
            color = Color(0xFF9ED6A8),
            fontSize = 15.sp
        )

        if (pickedPhotos.isNotEmpty() || pickedVideos.isNotEmpty()) {
            Text("Your selection is ready to save.", color = Color.Gray)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Text("Unhide is supported on Android 10 and newer.", color = Color.Gray, fontSize = 13.sp)
        }
        val filteredFiles = savedFiles.filter { file ->
            when (mediaFilter) {
                "Photos" -> file.parentFile == privatePhotosDir
                "Videos" -> file.parentFile == privateVideosDir
                else -> true
            }
        }
        // Keep the action area at a constant height. When an upward drag removes
        // the final selected item, the grid must not jump to fill the disappearing rows.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (selectedPrivateFiles.isNotEmpty() || privateSelectionDragging) 88.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedPrivateFiles.isNotEmpty()) {
                val selectedPrivateMedia = savedFiles.filter { file ->
                    file.absolutePath in selectedPrivateFiles && when (mediaFilter) {
                        "Photos" -> file.parentFile == privatePhotosDir
                        "Videos" -> file.parentFile == privateVideosDir
                        else -> true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { previewFiles = selectedPrivateMedia; previewIndex = 0 },
                        modifier = Modifier.weight(1f)
                    ) { Text("Preview (${selectedPrivateMedia.size})") }
                    Button(
                        onClick = { showDeletePrivateConfirm = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B3030))
                    ) { Text("Delete (${selectedPrivateMedia.size})") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val selected = savedFiles.filter { it.absolutePath in selectedPrivateFiles }
                            scope.launch {
                                var restored = 0
                                selected.forEach { file ->
                                    if (restorePrivateFile(file)) restored++
                                }
                                selectedPrivateFiles = emptySet()
                                refreshSavedFiles()
                                Toast.makeText(context, "Restored $restored of ${selected.size} files to Gallery", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                        modifier = Modifier.weight(1f)
                    ) { Text("Unhide (${selectedPrivateFiles.size})") }
                    OutlinedButton(
                        onClick = { selectedPrivateFiles = emptySet() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved Media", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val visiblePaths = filteredFiles.map { it.absolutePath }.toSet()
                    selectedPrivateFiles = if (visiblePaths.isNotEmpty() && visiblePaths.all { it in selectedPrivateFiles }) {
                        selectedPrivateFiles - visiblePaths
                    } else {
                        selectedPrivateFiles + visiblePaths
                    }
                }
            ) {
                Text(
                    if (filteredFiles.isNotEmpty() && filteredFiles.all { it.absolutePath in selectedPrivateFiles }) "Deselect All" else "Select All",
                    color = Color.White
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Photos", "Videos").forEach { filter ->
                Button(
                    onClick = { mediaFilter = filter },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mediaFilter == filter) Color(0xFF6550A4) else Color(0xFF29262D)
                    )
                ) { Text(filter) }
            }
        }
        if (filteredFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved media yet.", color = Color.Gray)
            }
        } else {
            val latestPrivateSelected = rememberUpdatedState(selectedPrivateFiles)
            val privateGridState = rememberLazyGridState()
            val privateSelectionScope = rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(filteredFiles) {
                        var lastX = 0f
                        var lastY = 0f
                        var privateEdgeDirection = 0
                        var trackingPrivateSelection = false
                        var privateAutoScrollJob: kotlinx.coroutines.Job? = null
                        var dragSelectedPaths = emptySet<String>()
                        var lastPrivateRow = -1
                        var accumulatedDy = 0f
                        var privateDragDirection = 1

                        // Match the main Gallery drag-selection behavior by selecting
                        // the full three-item row and every row crossed during auto-scroll.
                        fun selectPrivateAt(y: Float) {
                            val visibleItems = privateGridState.layoutInfo.visibleItemsInfo
                            val hit = visibleItems.firstOrNull { info ->
                                y >= info.offset.y && y < info.offset.y + info.size.height
                            } ?: visibleItems.minByOrNull { info ->
                                kotlin.math.abs((info.offset.y + info.size.height / 2f) - y)
                            } ?: return
                            val targetRow = hit.index / 3
                            val rows = if (lastPrivateRow < 0) {
                                listOf(targetRow)
                            } else if (lastPrivateRow <= targetRow) {
                                (lastPrivateRow..targetRow).toList()
                            } else {
                                (lastPrivateRow downTo targetRow).toList()
                            }
                            val rowPaths = rows.flatMap { row ->
                                val start = row * 3
                                (start until (start + 3).coerceAtMost(filteredFiles.size))
                                    .mapNotNull { filteredFiles.getOrNull(it)?.absolutePath }
                            }.toSet()
                            val updated = if (privateDragDirection > 0) {
                                dragSelectedPaths + rowPaths
                            } else {
                                dragSelectedPaths - rowPaths
                            }
                            if (updated != dragSelectedPaths) {
                                dragSelectedPaths = updated
                                selectedPrivateFiles = updated
                            }
                            lastPrivateRow = targetRow
                        }

                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                // The grid owns the long-press gesture so selection can continue
                                // immediately as the same finger moves across thumbnails.
                                trackingPrivateSelection = true
                                privateSelectionDragging = true
                                if (trackingPrivateSelection) {
                                    dragSelectedPaths = latestPrivateSelected.value
                                    lastX = start.x
                                    lastY = start.y
                                    privateEdgeDirection = 0
                                    lastPrivateRow = -1
                                    accumulatedDy = 0f
                                    privateDragDirection = 1
                                    selectPrivateAt(start.y)
                                }
                            },
                            onDrag = { change, _ ->
                                if (trackingPrivateSelection) {
                                    change.consume()
                                    val pos = change.position
                                    val dy = pos.y - lastY
                                    accumulatedDy += dy
                                    if (accumulatedDy >= 10f) {
                                        privateDragDirection = 1
                                        accumulatedDy = 0f
                                    } else if (accumulatedDy <= -10f) {
                                        privateDragDirection = -1
                                        accumulatedDy = 0f
                                    }
                                    selectPrivateAt(pos.y)
                                    lastX = pos.x
                                    lastY = pos.y

                                    val edge = 110.dp.toPx()
                                    privateEdgeDirection = when {
                                        pos.y <= edge -> -1
                                        pos.y >= size.height - edge -> 1
                                        else -> 0
                                    }
                                    if (privateEdgeDirection != 0 && privateAutoScrollJob?.isActive != true) {
                                        privateAutoScrollJob = privateSelectionScope.launch {
                                            while (privateEdgeDirection != 0 && trackingPrivateSelection) {
                                                privateGridState.scrollBy(privateEdgeDirection * 9f)
                                                selectPrivateAt(lastY)
                                                kotlinx.coroutines.delay(16L)
                                            }
                                        }
                                    } else if (privateEdgeDirection == 0) {
                                        privateAutoScrollJob?.cancel()
                                        privateAutoScrollJob = null
                                    }
                                }
                            },
                            onDragEnd = {
                                trackingPrivateSelection = false
                                privateSelectionDragging = false
                                privateEdgeDirection = 0
                                privateAutoScrollJob?.cancel()
                                privateAutoScrollJob = null
                            },
                            onDragCancel = {
                                trackingPrivateSelection = false
                                privateSelectionDragging = false
                                privateEdgeDirection = 0
                                privateAutoScrollJob?.cancel()
                                privateAutoScrollJob = null
                            }
                        )
                    }
            ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = privateGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredFiles, key = { it.absolutePath }) { file ->
                    val isVideoFile = file.parentFile == privateVideosDir
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file.absolutePath) {
                        value = withContext(Dispatchers.IO) {
                            if (isVideoFile) {
                                try {
                                    if (Build.VERSION.SDK_INT >= 29) {
                                        android.media.ThumbnailUtils.createVideoThumbnail(
                                            file,
                                            android.util.Size(360, 360),
                                            null
                                        )
                                    } else {
                                        android.media.ThumbnailUtils.createVideoThumbnail(
                                            file.absolutePath,
                                            MediaStore.Video.Thumbnails.MINI_KIND
                                        )
                                    }
                                } catch (_: Exception) { null }
                            } else {
                                try {
                                    val bounds = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                                    var sampleSize = 1
                                    while (
                                        bounds.outWidth / sampleSize > 400 ||
                                        bounds.outHeight / sampleSize > 400
                                    ) {
                                        sampleSize *= 2
                                    }
                                    val options = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = sampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                                } catch (_: Exception) { null }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable {
                                if (selectedPrivateFiles.isNotEmpty()) {
                                    selectedPrivateFiles = if (file.absolutePath in selectedPrivateFiles) {
                                        selectedPrivateFiles - file.absolutePath
                                    } else selectedPrivateFiles + file.absolutePath
                                } else {
                                    previewFiles = filteredFiles
                                    previewIndex = filteredFiles.indexOf(file).coerceAtLeast(0)
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262329))
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = if (isVideoFile) "Private video thumbnail" else "Private photo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(if (isVideoFile) "▶ VIDEO" else "PHOTO", color = Color.White)
                            }
                            if (isVideoFile) {
                                Text(
                                    "▶",
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .background(Color(0x99000000), RoundedCornerShape(30))
                                        .padding(8.dp)
                                )
                            }
                            if (file.absolutePath in selectedPrivateFiles) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(26.dp)
                                        .background(Color(0xFF6550A4), RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showDeletePrivateConfirm) {
        AlertDialog(
            onDismissRequest = { showDeletePrivateConfirm = false },
            title = { Text("Delete private files?") },
            text = { Text("This will permanently delete the selected files from Privacy Folder.") },
            confirmButton = {
                TextButton(onClick = {
                    val targets = savedFiles.filter { it.absolutePath in selectedPrivateFiles }
                    val deleted = targets.count { it.delete() }
                    selectedPrivateFiles = emptySet()
                    showDeletePrivateConfirm = false
                    refreshSavedFiles()
                    Toast.makeText(context, "Deleted $deleted of ${targets.size} private files", Toast.LENGTH_LONG).show()
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePrivateConfirm = false }) { Text("Cancel") }
            }
        )
    }
}


private fun pinDigest(pin: String): String = MessageDigest.getInstance("SHA-256")
    .digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }


@Composable
private fun PrivacyPinGate(
    existingHash: String?,
    changeMode: Boolean,
    onCancelChange: () -> Unit,
    onSuccess: () -> Unit,
    onPinSaved: (String) -> Unit,
    fingerprintEnabled: Boolean
) {
    val context = LocalContext.current
    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onSuccess()
    }
    var input by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(if (existingHash == null || changeMode) "new" else "unlock") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Privacy Folder", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(when(stage) { "unlock" -> "Enter PIN"; "confirm" -> "Confirm PIN"; else -> "Set 4-digit PIN" }, color = Color.LightGray, fontSize = 17.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(4) { i -> Box(Modifier.size(16.dp).background(if (input.length > i) Color(0xFF9B59FF) else Color.DarkGray, RoundedCornerShape(50))) } }
        Spacer(Modifier.height(16.dp))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF6B6B))
        if (fingerprintEnabled && stage == "unlock" && !changeMode) {
            TextButton(onClick = {
                val km = context.getSystemService(Activity.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isDeviceSecure) {
                    val promptIntent = km.createConfirmDeviceCredentialIntent("Unlock Privacy Folder", "Confirm your device lock")
                    if (promptIntent != null) credentialLauncher.launch(promptIntent) else error = "Device authentication is unavailable"
                } else error = "Set up fingerprint or screen lock in device settings first"
            }) { Text("Use fingerprint to unlock 🔐", color = Color(0xFFB98AFF)) }
        }
        listOf("1","2","3","4","5","6","7","8","9","⌫","0","✓").chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                row.forEach { key ->
                    Button(onClick = {
                        error = ""
                        when(key) {
                            "⌫" -> input = input.dropLast(1)
                            "✓" -> if (input.length == 4) when(stage) {
                                "unlock" -> if (pinDigest(input) == existingHash) onSuccess() else { error = "Incorrect PIN"; input = "" }
                                "new" -> { firstPin = input; input = ""; stage = "confirm" }
                                "confirm" -> if (input == firstPin) onPinSaved(pinDigest(input)) else { error = "PINs don't match"; input = ""; stage = "new" }
                            }
                            else error = "Enter all 4 digits"
                            else -> if(input.length < 4) input += key
                        }
                    }, modifier = Modifier.size(width = 82.dp, height = 58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29232F))) { Text(key, color = Color.White, fontSize = 20.sp) }
                }
            }
        }
        if (changeMode) TextButton(onClick = onCancelChange) { Text("Cancel", color = Color.LightGray) }
    }
}

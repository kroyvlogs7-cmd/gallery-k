package com.galleryk.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

data class GalleryMedia(
val uri: Uri,
val isVideo: Boolean,
val duration: Long,
val albumId: String,
val albumName: String,
val dateAdded: Long
)

data class PhotoAlbum(
val id: String,
val name: String,
val media: List<GalleryMedia>
)

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

        val projection = if (isVideo) {  
            arrayOf(  
                idColumnName,  
                bucketIdName,  
                bucketNameName,  
                dateName,  
                MediaStore.Video.VideoColumns.DURATION  
            )  
        } else {  
            arrayOf(  
                idColumnName,  
                bucketIdName,  
                bucketNameName,  
                dateName  
            )  
        }  

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

            val durationIndex = if (isVideo) {  
                cursor.getColumnIndexOrThrow(  
                    MediaStore.Video.VideoColumns.DURATION  
                )  
            } else -1  

            while (cursor.moveToNext()) {  
                val id = cursor.getLong(idIndex)  
                val uri = ContentUris.withAppendedId(collection, id)  
                val bucketId =  
                    cursor.getString(bucketIdIndex) ?: "unknown"  
                val bucketName =  
                    cursor.getString(bucketNameIndex) ?: "Other"  
                val dateAdded = cursor.getLong(dateIndex)  

                val duration = if (isVideo) {  
                    cursor.getLong(durationIndex)  
                } else 0L  

                allMedia.add(  
                    GalleryMedia(  
                        uri,  
                        isVideo,  
                        duration,  
                        bucketId,  
                        bucketName,  
                        dateAdded  
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
var selectedAlbumId by remember { mutableStateOf<String?>(null) }  
var selectedTab by remember { mutableStateOf("Photos") }  
var permissionGranted by remember { mutableStateOf(false) }  
var loading by remember { mutableStateOf(false) }  
var selectionMode by remember { mutableStateOf(false) }  
var selectedUris by remember { mutableStateOf(setOf<Uri>()) }  
var refreshKey by remember { mutableIntStateOf(0) }  
var privacyFolderScreen by remember { mutableStateOf(false) }
var privacyPinUnlocked by remember { mutableStateOf(false) }
var privacyPinChanging by remember { mutableStateOf(false) }
var privacyPickerActive by remember { mutableStateOf(false) }
val pinPrefs = remember { context.getSharedPreferences("gallery_k_privacy_pin", android.content.Context.MODE_PRIVATE) }
var storedPinHash by remember { mutableStateOf(pinPrefs.getString("pin_hash", null)) }
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
            Toast.makeText(  
                context,  
                "Selected media deleted",  
                Toast.LENGTH_SHORT  
            ).show()  
        } else {  
            Toast.makeText(  
                context,  
                "Deletion cancelled",  
                Toast.LENGTH_SHORT  
            ).show()  
        }  

        selectedUris = emptySet()  
        selectionMode = false  
        refreshKey++  
    }  

LaunchedEffect(Unit) {  
    permissionGranted = hasMediaPermission(context)  
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

fun requestDelete() {  
    if (selectedUris.isEmpty()) return  

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  
        try {  
            val request = MediaStore.createDeleteRequest(  
                context.contentResolver,  
                selectedUris.toList()  
            )  

            deleteLauncher.launch(  
                IntentSenderRequest.Builder(  
                    request.intentSender  
                ).build()  
            )  
        } catch (e: Exception) {  
            Toast.makeText(  
                context,  
                "Could not start delete confirmation",  
                Toast.LENGTH_LONG  
            ).show()  
        }  
    } else {  
        Toast.makeText(  
            context,  
            "Delete confirmation requires Android 11 or newer",  
            Toast.LENGTH_LONG  
        ).show()  
    }  
}  

BackHandler(  
    enabled = privacyFolderScreen || selectionMode ||  
        selectedIndex >= 0 ||  
        selectedAlbumId != null ||  
        selectedTab != "Photos"  
) {  
    when {  
        privacyFolderScreen -> { privacyFolderScreen = false; privacyPinUnlocked = false; privacyPinChanging = false }
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
                    onCancelChange = { privacyPinChanging = false },
                    onSuccess = { privacyPinUnlocked = true; privacyPinChanging = false },
                    onPinSaved = { hash -> pinPrefs.edit().putString("pin_hash", hash).apply(); storedPinHash = hash; privacyPinUnlocked = true; privacyPinChanging = false }
                )
            } else {
                PrivacyFolderScreen(
                    onBack = { privacyFolderScreen = false; privacyPinUnlocked = false; privacyPickerActive = false },
                    onOriginalsDeleted = { refreshKey++ },
                    onChangePin = { privacyPinChanging = true; privacyPinUnlocked = false },
                    onPickerStart = { privacyPickerActive = true },
                    onPickerEnd = { privacyPickerActive = false }
                )
            }
        } 
        else if (  
            selectedIndex >= 0 &&  
            selectedIndex < displayedMedia.size  
        ) {  
            val current = displayedMedia[selectedIndex]  

            if (current.isVideo) {  
                VideoPlayer(  
                    uri = current.uri,  
                    onBack = { selectedIndex = -1 }  
                )  
            } else {  
                PhotoViewer(  
                    uri = current.uri,  
                    index = selectedIndex,  
                    total = displayedMedia.size,  
                    onBack = { selectedIndex = -1 }  
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

                        Text(  
                            "${albums.size} Albums",  
                            color = Color.LightGray,  
                            modifier = Modifier.padding(  
                                start = 20.dp,  
                                bottom = 10.dp  
                            )  
                        )  

                        if (albums.isEmpty()) {  
                            Box(  
                                Modifier.fillMaxSize(),  
                                contentAlignment = Alignment.Center  
                            ) {  
                                Text(  
                                    "No albums found",  
                                    color = Color.White  
                                )  
                            }  
                        } else {  
                            LazyVerticalGrid(  
                                columns = GridCells.Fixed(2),  
                                contentPadding = PaddingValues(8.dp),  
                                modifier = Modifier.fillMaxSize()  
                            ) {  
                                items(albums, key = { it.id }) { album ->  
                                    AlbumCard(album) {  
                                        selectedAlbumId = album.id  
                                    }  
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

                        LazyVerticalGrid(  
                            columns = GridCells.Fixed(3),  
                            contentPadding = PaddingValues(3.dp),  
                            modifier = Modifier.fillMaxSize()  
                        ) {  
                            items(  
                                displayedMedia,  
                                key = { it.uri.toString() }  
                            ) { media ->  
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

                                            if (selectedUris.isEmpty()) {  
                                                selectionMode = false  
                                            }  
                                        } else {  
                                            selectedIndex =  
                                                displayedMedia.indexOf(media)  
                                        }  
                                    },  
                                    onLongClick = {  
                                        selectionMode = true  
                                        selectedUris =  
                                            selectedUris + media.uri  
                                    }  
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
onLongClick: () -> Unit
) {
val context = LocalContext.current

val bitmap by produceState<Bitmap?>(  
    initialValue = null,  
    key1 = media.uri  
) {  
    value = withContext(Dispatchers.IO) {  
        try {  
            if (Build.VERSION.SDK_INT >= 29) {  
                context.contentResolver.loadThumbnail(  
                    media.uri,  
                    android.util.Size(400, 400),  
                    null  
                )  
            } else {  
                context.contentResolver  
                    .openInputStream(media.uri)?.use {  
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
private fun PhotoViewer(
uri: Uri,
index: Int,
total: Int,
onBack: () -> Unit
) {
val context = LocalContext.current

var bitmap by remember(uri) {  
    mutableStateOf<Bitmap?>(null)  
}  

LaunchedEffect(uri) {  
    bitmap = withContext(Dispatchers.IO) {  
        try {  
            context.contentResolver  
                .openInputStream(uri)?.use {  
                    android.graphics.BitmapFactory.decodeStream(it)  
                }  
        } catch (_: Exception) {  
            null  
        }  
    }  
}  

Column(  
    Modifier  
        .fillMaxSize()  
        .background(Color.Black)  
) {  
    Row(  
        Modifier  
            .fillMaxWidth()  
            .padding(16.dp),  
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
            fontSize = 17.sp  
        )  
    }  

    Box(  
        Modifier  
            .weight(1f)  
            .fillMaxWidth(),  
        contentAlignment = Alignment.Center  
    ) {  
        if (bitmap != null) {  
            Image(  
                bitmap = bitmap!!.asImageBitmap(),  
                contentDescription = "Full photo",  
                modifier = Modifier.fillMaxSize(),  
                contentScale = ContentScale.Fit  
            )  
        } else {  
            CircularProgressIndicator(color = Color.White)  
        }  
    }  
}

}

@Composable
private fun VideoPlayer(
uri: Uri,
onBack: () -> Unit
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
            .pointerInput(exoPlayer, density) {  
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
    onPickerEnd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedPhotos by remember { mutableStateOf(listOf<Uri>()) }
    var pickedVideos by remember { mutableStateOf(listOf<Uri>()) }
    var saving by remember { mutableStateOf(false) }
    var savedPhotoCount by remember { mutableIntStateOf(0) }
    var savedVideoCount by remember { mutableIntStateOf(0) }
    var savedFiles by remember { mutableStateOf(listOf<File>()) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var mediaFilter by remember { mutableStateOf("All") }
    var selectedPrivateFiles by remember { mutableStateOf(setOf<String>()) }


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

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pickedPhotos = uris
        onPickerEnd()
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pickedVideos = uris
        onPickerEnd()
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

    fun launchOriginalDeleteRequest(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, uris)
                deleteOriginalsLauncher.launch(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
            } catch (_: Exception) {
                Toast.makeText(context, "Private copies saved, but Gallery removal could not be started.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Private copies saved. Android requires a separate delete permission on this version.", Toast.LENGTH_LONG).show()
        }
    }




    val openFile = previewFile
    if (openFile != null) {
        val uri = Uri.fromFile(openFile)
        if (openFile.parentFile == privateVideosDir) {
            VideoPlayer(uri = uri, onBack = { previewFile = null })
        } else {
            PhotoViewer(
                uri = uri,
                index = 0,
                total = 1,
                onBack = { previewFile = null }
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "‹  Back",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Text(
            text = "Privacy Folder",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text("Choose photos and videos", color = Color.LightGray, fontSize = 16.sp)
        OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) { Text("Change PIN") }

        Button(
            onClick = {
                onPickerStart()
                photoPicker.launch(arrayOf("image/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Photos (${pickedPhotos.size})") }

        Button(
            onClick = {
                onPickerStart()
                videoPicker.launch(arrayOf("video/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Videos (${pickedVideos.size})") }

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

                    // Originals are offered for removal only after private copies were saved.
                    val copiedSuccessfully = result.second.distinct()
                    if (copiedSuccessfully.isNotEmpty()) {
                        launchOriginalDeleteRequest(copiedSuccessfully)
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
        if (selectedPrivateFiles.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { selectedPrivateFiles = emptySet() },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel Selection") }
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
            }
        }

        Text("Saved Media", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
        val filteredFiles = savedFiles.filter { file ->
            when (mediaFilter) {
                "Photos" -> file.parentFile == privatePhotosDir
                "Videos" -> file.parentFile == privateVideosDir
                else -> true
            }
        }
        if (filteredFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved media yet.", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                } catch (_: Exception) { null }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    if (selectedPrivateFiles.isNotEmpty()) {
                                        selectedPrivateFiles = if (file.absolutePath in selectedPrivateFiles) {
                                            selectedPrivateFiles - file.absolutePath
                                        } else selectedPrivateFiles + file.absolutePath
                                    } else previewFile = file
                                },
                                onLongClick = {
                                    selectedPrivateFiles = selectedPrivateFiles + file.absolutePath
                                }
                            ),
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


private fun pinDigest(pin: String): String = MessageDigest.getInstance("SHA-256")
    .digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }


@Composable
private fun PrivacyPinGate(
    existingHash: String?,
    changeMode: Boolean,
    onCancelChange: () -> Unit,
    onSuccess: () -> Unit,
    onPinSaved: (String) -> Unit
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
        if (stage == "unlock" && !changeMode) {
            TextButton(onClick = {
                val keyguardManager = context.getSystemService(Activity.KEYGUARD_SERVICE) as KeyguardManager
                if (keyguardManager.isDeviceSecure) {
                    val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                        "Unlock Privacy Folder", "Confirm your device lock"
                    )
                    if (intent != null) credentialLauncher.launch(intent)
                    else error = "Device authentication is unavailable"
                } else {
                    error = "Set up fingerprint or screen lock in device settings first"
                }
            }) { Text("Unlock with device authentication", color = Color(0xFFB98AFF)) }
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

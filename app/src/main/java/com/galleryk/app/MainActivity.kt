package com.galleryk.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
var swipeSelectMode by remember { mutableStateOf(false) }
val gridState = rememberLazyGridState()
val swipeSelectScope = rememberCoroutineScope()
var refreshKey by remember { mutableIntStateOf(0) }
var privacyFolderOpen by remember { mutableStateOf(false) }

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
        swipeSelectMode = false
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
            val hiddenUris = context.getSharedPreferences("gallery_privacy", android.content.Context.MODE_PRIVATE).getStringSet("hidden_original_uris", emptySet()) ?: emptySet()
            allMedia = result.first.filterNot { it.uri.toString() in hiddenUris }
            albums = allMedia.groupBy { it.albumId }.map { (id, items) -> PhotoAlbum(id, items.firstOrNull()?.albumName ?: "Other", items) }
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
    enabled = privacyFolderOpen || selectionMode ||
        selectedIndex >= 0 ||
        selectedAlbumId != null ||
        selectedTab != "Photos"
) {
    when {
        privacyFolderOpen -> privacyFolderOpen = false
        selectionMode -> {
            selectionMode = false
            swipeSelectMode = false
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
        if (privacyFolderOpen) {
            PrivacyFolderScreen(onClose = { privacyFolderOpen = false }, onRefresh = { refreshKey++ })
        } else if (
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

                    if (!selectionMode) {
                        TextButton(
                            onClick = {
                                selectionMode = true
                                swipeSelectMode = true
                                selectedUris = emptySet()
                            }
                        ) {
                            Text("Swipe Select", color = Color.White)
                        }
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
                                swipeSelectMode = false
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

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                PrivacyFolderEntry(onClick = { privacyFolderOpen = true })
                            }
                            if (albums.isEmpty()) {
                                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        Modifier.fillMaxWidth().height(160.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No albums found", color = Color.White)
                                    }
                                }
                            } else {
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
                            state = gridState,
                            contentPadding = PaddingValues(3.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(swipeSelectMode, displayedMedia, gridState) {
                                    if (swipeSelectMode) {
                                        var lastSelectedIndex = -1

                                        fun selectAt(position: Offset) {
                                            val hitItem = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { item ->
                                                    position.x >= item.offset.x &&
                                                        position.x < item.offset.x + item.size.width &&
                                                        position.y >= item.offset.y &&
                                                        position.y < item.offset.y + item.size.height
                                                }
                                            val hitIndex = hitItem?.index ?: -1
                                            if (hitIndex >= 0 &&
                                                hitIndex != lastSelectedIndex &&
                                                hitIndex < displayedMedia.size
                                            ) {
                                                selectedUris =
                                                    selectedUris + displayedMedia[hitIndex].uri
                                                lastSelectedIndex = hitIndex
                                            }
                                        }

                                        detectDragGestures(
                                            onDragStart = { start ->
                                                lastSelectedIndex = -1
                                                selectAt(start)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                swipeSelectScope.launch {
                                                    gridState.scrollBy(-dragAmount.y)
                                                }
                                                selectAt(change.position)
                                            },
                                            onDragEnd = {
                                                lastSelectedIndex = -1
                                            },
                                            onDragCancel = {
                                                lastSelectedIndex = -1
                                            }
                                        )
                                    }
                                }
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
                                                swipeSelectMode = false
                                            }
                                        } else {
                                            selectedIndex =
                                                displayedMedia.indexOf(media)
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        swipeSelectMode = false
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


@Composable
private fun PrivacyFolderEntry(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF272934))
        .clickable(onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔒", fontSize = 28.sp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text("Privacy Folder", color = Color.White, fontWeight = FontWeight.Bold); Text("PIN-protected private media", color = Color.LightGray, fontSize = 12.sp) }
        Text("›", color = Color.White, fontSize = 24.sp)
    }
}


@Composable
private fun PrivacyFolderScreen(onClose: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gallery_privacy", android.content.Context.MODE_PRIVATE) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(prefs.getString("pin_hash", null) == null) }
    var error by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dir = remember { File(context.filesDir, "privacy_media").apply { mkdirs() } }
    fun reload() { files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList() }
    LaunchedEffect(unlocked) { if (unlocked) reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) scope.launch {
            busy = true
            var copied = 0
            val hidden = prefs.getStringSet("hidden_original_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
            withContext(Dispatchers.IO) {
                picked.forEach { source ->
                    try {
                        val mime = context.contentResolver.getType(source) ?: "application/octet-stream"
                        val ext = when (mime) { "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/webp" -> ".webp"; "video/mp4" -> ".mp4"; else -> if (mime.startsWith("video/")) ".video" else if (mime.startsWith("image/")) ".image" else ".dat" }
                        val target = File(dir, "private_${System.currentTimeMillis()}_${(0..9999).random()}$ext")
                        context.contentResolver.openInputStream(source)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                        if (target.exists() && target.length() > 0) { prefs.edit().putString("mime_${target.name}", mime).putString("source_${target.name}", source.toString()).apply(); hidden.add(source.toString()); copied++ } else target.delete()
                    } catch (_: Exception) { }
                }
            }
            prefs.edit().putStringSet("hidden_original_uris", hidden).apply()
            busy = false; reload(); onRefresh()
            Toast.makeText(context, "$copied file(s) copied. Original files are still on device.", Toast.LENGTH_LONG).show()
        }
    }

    fun restore(file: File) {
        scope.launch {
            busy = true
            val mime = prefs.getString("mime_${file.name}", null) ?: if (file.extension == "mp4") "video/mp4" else "image/jpeg"
            val video = mime.startsWith("video/")
            val target = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Restored_${file.name.removePrefix("private_")}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/GalleryK Restored" else "Pictures/GalleryK Restored")
            }
            var restored = false
            withContext(Dispatchers.IO) {
                try { val uri = context.contentResolver.insert(target, values); if (uri != null) context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) }; restored = true } } catch (_: Exception) { }
            }
            busy = false
            if (restored) { Toast.makeText(context, "Restored into device gallery", Toast.LENGTH_SHORT).show(); file.delete(); val sourceUri = prefs.getString("source_${file.name}", null); val hiddenUris = prefs.getStringSet("hidden_original_uris", emptySet())?.toMutableSet() ?: mutableSetOf(); if (sourceUri != null) hiddenUris.remove(sourceUri); prefs.edit().putStringSet("hidden_original_uris", hiddenUris).remove("mime_${file.name}").remove("source_${file.name}").apply(); reload(); onRefresh() }
            else Toast.makeText(context, "Restore failed", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onClose) { Text("‹ Back", color = Color.White) }; Text("Privacy Folder", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        if (!unlocked) {
            Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔐", fontSize = 48.sp); Text(if (setup) "Create 4-digit PIN" else "Enter PIN", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp)); OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("PIN") }, singleLine = true)
                if (setup) OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(4) }, label = { Text("Confirm PIN") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8080), modifier = Modifier.padding(8.dp))
                Button(onClick = {
                    error = ""
                    if (pin.length != 4) error = "PIN must be 4 digits"
                    else if (setup && pin != confirmPin) error = "PINs don't match"
                    else {
                        val hash = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
                        if (setup) { prefs.edit().putString("pin_hash", hash).apply(); setup = false; unlocked = true }
                        else if (prefs.getString("pin_hash", null) == hash) unlocked = true else error = "Incorrect PIN"
                    }
                }, modifier = Modifier.padding(top = 10.dp)) { Text(if (setup) "Save PIN" else "Unlock") }
            }
            Text("PIN hash is saved locally on this device.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("${files.size} private files", color = Color.White, modifier = Modifier.weight(1f)); Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, enabled = !busy) { Text("Add media") } }
            Text("Files are copied to app-private storage and hidden inside Gallery K. Original media is NOT deleted from the device or other gallery apps.", color = Color(0xFFFFD080), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (files.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Privacy Folder is empty", color = Color.Gray) }
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) { items(files, key = { it.name }) { file ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF252832)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (file.extension in listOf("mp4", "video")) "🎞️" else "🖼️", fontSize = 22.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(file.name.removePrefix("private_"), color = Color.White, maxLines = 1); Text("${file.length()/1024} KB", color = Color.Gray, fontSize = 11.sp) }
                    TextButton(enabled = !busy, onClick = { restore(file) }) { Text("Restore") }
                    TextButton(enabled = !busy, onClick = { file.delete(); prefs.edit().remove("mime_${file.name}").apply(); reload() }) { Text("Delete", color = Color(0xFFFF8888)) }
                }
            } }
        }
    }
}

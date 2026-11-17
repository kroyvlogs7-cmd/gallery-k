
package com.galleryk.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.TextureView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

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
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasMediaPermission(context: Context): Boolean {
    return requiredPermissions().any {
        ContextCompat.checkSelfPermission(context, it) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private suspend fun loadGallery(
    context: Context
): Pair<List<GalleryMedia>, List<PhotoAlbum>> =
    withContext(Dispatchers.IO) {
        val allMedia = mutableListOf<GalleryMedia>()

        fun readCollection(collection: Uri, isVideo: Boolean) {
            val idColumn = MediaStore.MediaColumns._ID
            val bucketIdColumn = MediaStore.Images.ImageColumns.BUCKET_ID
            val bucketNameColumn =
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME
            val dateColumn = MediaStore.MediaColumns.DATE_ADDED

            val projection = if (isVideo) {
                arrayOf(
                    idColumn,
                    bucketIdColumn,
                    bucketNameColumn,
                    dateColumn,
                    MediaStore.Video.VideoColumns.DURATION
                )
            } else {
                arrayOf(idColumn, bucketIdColumn, bucketNameColumn, dateColumn)
            }

            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "$dateColumn DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(idColumn)
                val bucketIdIndex =
                    cursor.getColumnIndexOrThrow(bucketIdColumn)
                val bucketNameIndex =
                    cursor.getColumnIndexOrThrow(bucketNameColumn)
                val dateIndex = cursor.getColumnIndexOrThrow(dateColumn)
                val durationIndex = if (isVideo) {
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Video.VideoColumns.DURATION
                    )
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    allMedia.add(
                        GalleryMedia(
                            uri = ContentUris.withAppendedId(collection, id),
                            isVideo = isVideo,
                            duration = if (isVideo) {
                                cursor.getLong(durationIndex)
                            } else 0L,
                            albumId = cursor.getString(bucketIdIndex) ?: "unknown",
                            albumName = cursor.getString(bucketNameIndex) ?: "Other",
                            dateAdded = cursor.getLong(dateIndex)
                        )
                    )
                }
            }
        }

        readCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        readCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)

        val sorted = allMedia.sortedByDescending { it.dateAdded }
        val albums = sorted.groupBy { it.albumId }.map { (id, media) ->
            PhotoAlbum(
                id = id,
                name = media.firstOrNull()?.albumName ?: "Other",
                media = media
            )
        }

        Pair(sorted, albums)
    }

@Composable
fun GalleryKApp() {
    val context = LocalContext.current

    var allMedia by remember { mutableStateOf(listOf<GalleryMedia>()) }
    var albums by remember { mutableStateOf(listOf<PhotoAlbum>()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf("Photos") }
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = hasMediaPermission(context)
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Toast.makeText(
            context,
            if (result.resultCode == Activity.RESULT_OK) {
                "Selected media deleted"
            } else {
                "Deletion cancelled"
            },
            Toast.LENGTH_SHORT
        ).show()

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Could not load media: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                loading = false
            }
        }
    }

    val currentAlbum = albums.find { it.id == selectedAlbumId }

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
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            } catch (_: Exception) {
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
        enabled = selectionMode ||
            selectedIndex >= 0 ||
            selectedAlbumId != null ||
            selectedTab != "Photos"
    ) {
        when {
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
            if (selectedIndex >= 0 && selectedIndex < displayedMedia.size) {
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
                        Modifier.fillMaxWidth().padding(20.dp),
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
                                        if (selectedUris.size == displayedMedia.size) {
                                            emptySet()
                                        } else {
                                            displayedMedia.map { it.uri }.toSet()
                                        }
                                }
                            ) {
                                Text(
                                    if (selectedUris.size == displayedMedia.size) {
                                        "Deselect All"
                                    } else "Select All",
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (selectionMode) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        permissionLauncher.launch(requiredPermissions())
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

                        selectedTab == "Albums" && selectedAlbumId == null -> {
                            Text(
                                "${albums.size} Albums",
                                color = Color.LightGray,
                                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
                            )

                            if (albums.isEmpty()) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No albums found", color = Color.White)
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
                                    if (selectedTab == "Videos") "Videos" else "Photos"
                                }",
                                color = Color.LightGray,
                                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
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
                                                selectedIndex = displayedMedia.indexOf(media)
                                            }
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
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: PhotoAlbum, onClick: () -> Unit) {
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
        Text("${album.media.size} items", color = Color.Gray, fontSize = 12.sp)
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
        value = try {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(
                        media.uri,
                        android.util.Size(400, 400),
                        null
                    )
                } else {
                    context.contentResolver.openInputStream(media.uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
                contentDescription = if (media.isVideo) "Video thumbnail" else "Photo thumbnail",
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
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("▶", color = Color.White, fontSize = 16.sp)
            }

            Text(
                formatDuration(media.duration),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        if (selected) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    "✓",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color(0xFF2878D0), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, remainder)
    } else {
        String.format("%d:%02d", minutes, remainder)
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
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Spacer(Modifier.width(20.dp))
            Text("Photo ${index + 1} / $total", color = Color.White, fontSize = 17.sp)
        }

        Box(
            Modifier.weight(1f).fillMaxWidth(),
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
private fun VideoPlayer(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    val activity = remember(context) {
        var current: Context = context
        while (current !is Activity && current is ContextWrapper) {
            current = current.baseContext
        }
        current as? Activity
    }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            volume = 1f
            prepare()
        }
    }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var rotationDegrees by remember(uri) { mutableIntStateOf(0) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var panX by remember(uri) { mutableFloatStateOf(0f) }
    var panY by remember(uri) { mutableFloatStateOf(0f) }

    var indicatorType by remember { mutableStateOf("") }
    var indicatorLevel by remember { mutableFloatStateOf(0f) }
    var indicatorVisible by remember { mutableStateOf(false) }
    var indicatorResetKey by remember { mutableIntStateOf(0) }

    var controlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }

    fun resetVideoView() {
        rotationDegrees = 0
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    fun formatTime(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0L)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, remainder)
        } else {
            String.format("%02d:%02d", minutes, remainder)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                controlsVisible = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Video dimensions are handled by TextureView's fit mode.
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = error.message ?: "Unable to play this video"
                controlsVisible = true
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
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                sliderPosition = currentPosition.toFloat()
            }
            delay(250)
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
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        val controller = window?.let {
            WindowInsetsControllerCompat(it, it.decorView)
        }

        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            try {
                textureView?.let { view ->
                    exoPlayer.clearVideoTextureView(view)
                }
                textureView = null
                exoPlayer.release()
            } catch (_: Exception) {
                // Player may already be released.
            }

            window?.let {
                val params = it.attributes
                params.screenBrightness = originalBrightness
                it.attributes = params
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        resetVideoView()
        onBack()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
    ) {
        val screenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        val rotationFit = if (rotationDegrees % 180 != 0) {
            min(
                screenWidthPx / screenHeightPx,
                screenHeightPx / screenWidthPx
            )
        } else 1f

        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    textureView = this
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { view ->
                if (textureView !== view) {
                    textureView?.let { old ->
                        exoPlayer.clearVideoTextureView(old)
                    }
                    textureView = view
                    exoPlayer.setVideoTextureView(view)
                }
            },
            onRelease = { view ->
                if (textureView === view) {
                    exoPlayer.clearVideoTextureView(view)
                    textureView = null
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotationDegrees.toFloat()
                    val scale = zoom * rotationFit
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                }
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(zoom, rotationDegrees) {
                    var lastTapTime = 0L
                    var horizontalStart = 0f
                    var verticalStart = 0f
                    var startPosition = 0L
                    var startBrightness = 0.5f
                    var startVolume = 1f
                    var dragMode = ""

                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        controlsVisible = true

                        if (gestureZoom != 1f) {
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                            zoom = newZoom

                            if (newZoom <= 1.01f) {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            }
                        }

                        if (zoom > 1f && gestureZoom == 1f) {
                            val maxX = size.width * (zoom - 1f) / 2f
                            val maxY = size.height * (zoom - 1f) / 2f
                            panX = (panX + pan.x).coerceIn(-maxX, maxX)
                            panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        }
                    }
                }
                .pointerInput(exoPlayer, zoom, activity) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            val pressed = down.changes.firstOrNull { it.pressed }
                                ?: continue
                            val start = pressed.position
                            val startTime = System.currentTimeMillis()
                            val startX = start.x
                            val startY = start.y
                            var lastX = startX
                            var lastY = startY
                            var moved = false
                            var finished = false

                            while (!finished) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                    ?: continue

                                if (!change.pressed) {
                                    finished = true
                                    continue
                                }

                                val dx = change.position.x - lastX
                                val dy = change.position.y - lastY
                                lastX = change.position.x
                                lastY = change.position.y

                                if (abs(change.position.x - startX) > 12f ||
                                    abs(change.position.y - startY) > 12f
                                ) {
                                    moved = true
                                }

                                if (moved && zoom <= 1.01f) {
                                    if (dragMode.isEmpty()) {
                                        dragMode = if (
                                            abs(change.position.x - startX) >
                                            abs(change.position.y - startY)
                                        ) "horizontal" else "vertical"

                                        startPosition = exoPlayer.currentPosition

                                        if (dragMode == "vertical") {
                                            if (startX < size.width / 2f) {
                                                val b = activity?.window?.attributes
                                                    ?.screenBrightness ?: -1f
                                                startBrightness = if (b < 0f) 0.5f else b
                                                indicatorType = "brightness"
                                                indicatorLevel = startBrightness
                                            } else {
                                                startVolume = exoPlayer.volume
                                                indicatorType = "volume"
                                                indicatorLevel = startVolume
                                            }
                                            indicatorVisible = true
                                            indicatorResetKey++
                                        }
                                    }

                                    if (dragMode == "horizontal") {
                                        val changeMs = ((change.position.x - startX) /
                                            density * 100L).toLong()
                                        val target = (startPosition + changeMs)
                                            .coerceIn(
                                                0L,
                                                exoPlayer.duration.coerceAtLeast(0L)
                                            )
                                        exoPlayer.seekTo(target)
                                        currentPosition = target
                                        sliderPosition = target.toFloat()
                                    } else if (dragMode == "vertical") {
                                        val adjustment = (change.position.y - startY) /
                                            (size.height * 0.60f)

                                        if (indicatorType == "brightness") {
                                            val value = (startBrightness - adjustment)
                                                .coerceIn(0.01f, 1f)
                                            activity?.window?.let { window ->
                                                val params = window.attributes
                                                params.screenBrightness = value
                                                window.attributes = params
                                            }
                                            indicatorLevel = value
                                        } else {
                                            val value = (startVolume - adjustment)
                                                .coerceIn(0f, 1f)
                                            exoPlayer.volume = value
                                            indicatorLevel = value
                                        }
                                        indicatorVisible = true
                                        indicatorResetKey++
                                    }
                                }

                                change.consume()
                            }

                            if (!moved) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    if (exoPlayer.isPlaying) exoPlayer.pause()
                                    else exoPlayer.play()
                                    controlsVisible = true
                                    lastTapTime = 0L
                                } else {
                                    controlsVisible = !controlsVisible
                                    lastTapTime = now
                                }
                            }

                            if (dragMode == "vertical") {
                                indicatorVisible = true
                                indicatorResetKey++
                            }
                            dragMode = ""
                        }
                    }
                }
        )

        if (playerError != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Cannot play this video",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    playerError ?: "",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    playerError = null
                    exoPlayer.prepare()
                    exoPlayer.play()
                }) {
                    Text("Retry")
                }
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0x99000000))
                        .clickable {
                            rotationDegrees = (rotationDegrees + 90) % 360
                            controlsVisible = true
                        }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⟳", color = Color.White, fontSize = 24.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("Rotate", color = Color.White, fontSize = 12.sp)
                }

                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0x99000000))
                        .clickable {
                            resetVideoView()
                            controlsVisible = true
                        }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⤢", color = Color.White, fontSize = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset / Fit", color = Color.White, fontSize = 12.sp)
                }

                Text(
                    if (isPlaying) "Ⅱ" else "▶",
                    color = Color.White,
                    fontSize = 38.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x55000000))
                        .clickable {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            controlsVisible = true
                        }
                        .padding(18.dp)
                )

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x88000000))
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatTime(if (isScrubbing) sliderPosition.toLong() else currentPosition),
                            color = Color.White,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(duration), color = Color.White, fontSize = 11.sp)
                    }

                    Slider(
                        value = sliderPosition.coerceIn(
                            0f,
                            duration.coerceAtLeast(1L).toFloat()
                        ),
                        onValueChange = {
                            isScrubbing = true
                            sliderPosition = it
                            controlsVisible = true
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(sliderPosition.toLong())
                            currentPosition = sliderPosition.toLong()
                            isScrubbing = false
                        },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x66FFFFFF)
                        )
                    )
                }
            }
        }

        if (indicatorVisible && indicatorType.isNotEmpty()) {
            val left = indicatorType == "brightness"

            Column(
                Modifier
                    .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    if (left) "☀" else "◖))",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Box(
                    Modifier
                        .width(5.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x66FFFFFF))
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(indicatorLevel.coerceIn(0.01f, 1f))
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

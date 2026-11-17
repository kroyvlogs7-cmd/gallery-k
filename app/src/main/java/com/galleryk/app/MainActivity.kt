package com.galleryk.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.media.AudioManager
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
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

        fun readCollection(
            collection: Uri,
            isVideo: Boolean
        ) {
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
                val dateIndex = cursor.getColumnIndexOrThrow(dateName)

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
                    val duration =
                        if (isVideo) cursor.getLong(durationIndex) else 0L

                    allMedia.add(
                        GalleryMedia(
                            uri = uri,
                            isVideo = isVideo,
                            duration = duration,
                            albumId = bucketId,
                            albumName = bucketName,
                            dateAdded = dateAdded
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
    var selectedIndex by remember {
        mutableIntStateOf(-1)
    }
    var selectedAlbumId by remember {
        mutableStateOf<String?>(null)
    }
    var selectedTab by remember {
        mutableStateOf("Photos")
    }
    var permissionGranted by remember {
        mutableStateOf(false)
    }
    var loading by remember {
        mutableStateOf(false)
    }
    var selectionMode by remember {
        mutableStateOf(false)
    }
    var selectedUris by remember {
        mutableStateOf(setOf<Uri>())
    }
    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionGranted = hasMediaPermission(context)
    }

    val deleteLauncher = rememberLauncherForActivityResult(
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
            } catch (e: CancellationException) {
                throw e
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
            if (
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
                                if (selectionMode)
                                    "${selectedUris.size} Selected"
                                else "Gallery K",
                                color = Color.White,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                if (selectionMode)
                                    "Select media to manage"
                                else "Your memories, beautifully organized",
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
                                            displayedMedia
                                                .map { it.uri }
                                                .toSet()
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
                            listOf(
                                "Photos",
                                "Videos",
                                "Albums"
                            ).forEach { tab ->

                                val active = selectedTab == tab

                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(
                                            RoundedCornerShape(12.dp)
                                        )
                                        .background(
                                            if (active)
                                                Color(0xFF315C91)
                                            else
                                                Color(0xFF24262C)
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
                                    items(
                                        albums,
                                        key = { it.id }
                                    ) { album ->
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
                                    if (selectedTab == "Videos")
                                        "No videos found"
                                    else
                                        "No photos found",
                                    color = Color.White
                                )
                            }
                        }

                        else -> {
                            Text(
                                "${displayedMedia.size} ${
                                    if (selectedTab == "Videos")
                                        "Videos"
                                    else
                                        "Photos"
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
                                        selected =
                                            media.uri in selectedUris,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedUris =
                                                    if (
                                                        media.uri in selectedUris
                                                    ) {
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
                            android.graphics.BitmapFactory
                                .decodeStream(it)
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
                    if (media.isVideo)
                        "Video thumbnail"
                    else
                        "Photo thumbnail",
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
                    .padding(
                        horizontal = 9.dp,
                        vertical = 5.dp
                    )
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
                    .padding(
                        horizontal = 4.dp,
                        vertical = 2.dp
                    )
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
                        .padding(
                            horizontal = 7.dp,
                            vertical = 2.dp
                        )
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
        String.format(
            "%d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    } else {
        String.format(
            "%d:%02d",
            minutes,
            seconds
        )
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
                        android.graphics.BitmapFactory
                            .decodeStream(it)
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
                CircularProgressIndicator(
                    color = Color.White
                )
            }
        }
    }
}

private fun fitTextureView(
    texture: android.view.TextureView,
    videoSize: androidx.media3.common.VideoSize
) {
    val width = texture.width
    val height = texture.height
    if (width <= 0 || height <= 0 || videoSize.width <= 0 || videoSize.height <= 0) return
    val viewRatio = width.toFloat() / height.toFloat()
    val videoRatio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
    val sx = if (videoRatio > viewRatio) 1f else videoRatio / viewRatio
    val sy = if (videoRatio > viewRatio) viewRatio / videoRatio else 1f
    texture.setTransform(android.graphics.Matrix().apply {
        setScale(sx, sy, width / 2f, height / 2f)
    })
}

@Composable
private fun VideoPlayer(
    uri: Uri,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    }

    var playerError by remember(uri) { mutableStateOf<String?>(null) }
    var isBuffering by remember(uri) { mutableStateOf(true) }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var rotation by remember(uri) { mutableIntStateOf(0) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var panX by remember(uri) { mutableFloatStateOf(0f) }
    var panY by remember(uri) { mutableFloatStateOf(0f) }
    var currentPosition by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var sliderPosition by remember(uri) { mutableFloatStateOf(0f) }
    var scrubbing by remember(uri) { mutableStateOf(false) }
    var indicatorType by remember(uri) { mutableStateOf("") }
    var indicatorLevel by remember(uri) { mutableFloatStateOf(0f) }
    var indicatorVisible by remember(uri) { mutableStateOf(false) }
    var indicatorKey by remember(uri) { mutableIntStateOf(0) }
    var controlsKey by remember(uri) { mutableIntStateOf(0) }
    var lastTapTime by remember(uri) { mutableLongStateOf(0L) }
    var videoTexture by remember(uri) { mutableStateOf<android.view.TextureView?>(null) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = error.localizedMessage ?: "This video format could not be played."
                isBuffering = false
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
                    playbackState == androidx.media3.common.Player.STATE_IDLE
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
                videoTexture?.let { fitTextureView(it, videoSize) }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(activity, exoPlayer) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (window != null) {
                val params = window.attributes
                params.screenBrightness = originalBrightness
                window.attributes = params
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
            exoPlayer.clearVideoSurface()
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                duration = exoPlayer.duration.coerceAtLeast(0L)
                sliderPosition = if (duration > 0L) currentPosition.toFloat() / duration else 0f
            }
            delay(200)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, scrubbing, controlsKey) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(indicatorKey) {
        if (indicatorVisible) {
            delay(1500)
            indicatorVisible = false
        }
    }

    fun showControls() {
        controlsVisible = true
        controlsKey++
    }
    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    BackHandler { onBack() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        val rotated = rotation % 180 != 0
        val videoWidth = if (rotated) maxHeight else maxWidth
        val videoHeight = if (rotated) maxWidth else maxHeight

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { viewContext ->
                    android.view.TextureView(viewContext).apply {
                        isOpaque = false
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                exoPlayer.setVideoTextureView(this@apply)
                                videoTexture = this@apply
                                fitTextureView(this@apply, exoPlayer.videoSize)
                            }
                            override fun onSurfaceTextureSizeChanged(
                                surface: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                fitTextureView(this@apply, exoPlayer.videoSize)
                            }
                            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                                exoPlayer.clearVideoTextureView(this@apply)
                                if (videoTexture === this@apply) videoTexture = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                        }
                    }
                },
                update = { texture ->
                    videoTexture = texture
                    if (texture.isAvailable) {
                        exoPlayer.setVideoTextureView(texture)
                        fitTextureView(texture, exoPlayer.videoSize)
                    }
                },
                modifier = Modifier
                    .width(videoWidth)
                    .height(videoHeight)
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                    }
            )
        }

        // Gesture handling: two fingers pinch/pan; one finger seek, brightness or volume.
        Box(
            Modifier.fillMaxSize().pointerInput(exoPlayer) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 0 tap, 1 seek, 2 brightness, 3 volume, 4 zoom-pan
                    var finished = false
                    var start = first.position
                    var previousCentroid = first.position
                    var previousSpan = 0f
                    val startPositionMs = exoPlayer.currentPosition
                    val screenW = size.width.toFloat().coerceAtLeast(1f)
                    val screenH = size.height.toFloat().coerceAtLeast(1f)
                    val startBrightness = activity?.window?.attributes?.screenBrightness
                        ?.takeIf { it >= 0f } ?: 0.5f
                    val startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    var moved = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val centroid = (p1 + p2) / 2f
                            val span = (p1 - p2).getDistance().coerceAtLeast(1f)
                            if (mode != 4) {
                                mode = 4
                                previousCentroid = centroid
                                previousSpan = span
                            } else {
                                val factor = span / previousSpan.coerceAtLeast(1f)
                                zoom = (zoom * factor).coerceIn(1f, 5f)
                                if (zoom <= 1.001f) {
                                    zoom = 1f
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    panX += centroid.x - previousCentroid.x
                                    panY += centroid.y - previousCentroid.y
                                }
                                previousCentroid = centroid
                                previousSpan = span
                            }
                            moved = true
                            event.changes.forEach { it.consume() }
                        } else if (pressed.isEmpty()) {
                            finished = true
                        } else {
                            val change = pressed.first()
                            val dx = change.position.x - start.x
                            val dy = change.position.y - start.y
                            if (mode == 0 && (kotlin.math.abs(dx) > viewConfiguration.touchSlop || kotlin.math.abs(dy) > viewConfiguration.touchSlop)) {
                                moved = true
                                mode = when {
                                    zoom > 1.01f -> 4
                                    kotlin.math.abs(dx) > kotlin.math.abs(dy) -> 1
                                    start.x < screenW / 2f -> 2
                                    else -> 3
                                }
                            }
                            when (mode) {
                                1 -> {
                                    if (duration > 0L) {
                                        val delta = (dx / screenW * duration).toLong()
                                        exoPlayer.seekTo((startPositionMs + delta).coerceIn(0L, duration))
                                    }
                                    change.consume()
                                }
                                2 -> {
                                    val value = (startBrightness - dy / screenH).coerceIn(0.01f, 1f)
                                    activity?.window?.let { w ->
                                        val params = w.attributes
                                        params.screenBrightness = value
                                        w.attributes = params
                                    }
                                    indicatorType = "brightness"
                                    indicatorLevel = value
                                    indicatorVisible = true
                                    indicatorKey++
                                    change.consume()
                                }
                                3 -> {
                                    val value = (startVolume - dy / screenH * maxVolume).toInt().coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                    indicatorType = "volume"
                                    indicatorLevel = value.toFloat() / maxVolume
                                    indicatorVisible = true
                                    indicatorKey++
                                    change.consume()
                                }
                                4 -> {
                                    panX += dx
                                    panY += dy
                                    start = change.position
                                    change.consume()
                                }
                            }
                        }
                    }
                    if (!moved) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapTime <= 300L && lastTapTime != 0L) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            showControls()
                        }
                    }
                }
            }
        )

        if (isBuffering && playerError == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        if (indicatorVisible) {
            val pct = (indicatorLevel.coerceIn(0f, 1f) * 100).toInt()
            Column(
                Modifier.align(if (indicatorType == "brightness") Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 22.dp)
                    .width(44.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x99000000))
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (indicatorType == "brightness") "☀" else "♪", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(5.dp).height(68.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x66FFFFFF)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(indicatorLevel.coerceIn(0.02f, 1f)).background(Color.White))
                }
                Spacer(Modifier.height(6.dp))
                Text("$pct%", color = Color.White, fontSize = 10.sp)
            }
        }

        if (controlsVisible && playerError == null) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp))
                Spacer(Modifier.weight(1f))
                Text("↻  Rotate", color = Color.White, modifier = Modifier.clickable {
                    rotation = (rotation + 90) % 360
                    resetView()
                    showControls()
                }.padding(10.dp))
                Text("Fit", color = Color.White, modifier = Modifier.clickable {
                    resetView()
                    showControls()
                }.padding(10.dp))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x77000000)).padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(currentPosition), color = Color.White, fontSize = 11.sp)
                    Slider(
                        value = sliderPosition.coerceIn(0f, 1f),
                        onValueChange = {
                            scrubbing = true
                            sliderPosition = it
                        },
                        onValueChangeFinished = {
                            if (duration > 0L) exoPlayer.seekTo((sliderPosition * duration).toLong())
                            scrubbing = false
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(formatTime(duration), color = Color.White, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isPlaying) "Ⅱ" else "▶", color = Color.White, fontSize = 28.sp,
                        modifier = Modifier.clickable {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showControls()
                        }.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
        }

        if (playerError != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this video", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(playerError ?: "Playback error", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Back to Gallery") }
            }
        }
    }
}

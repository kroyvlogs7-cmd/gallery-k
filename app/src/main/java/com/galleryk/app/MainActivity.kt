
package com.galleryk.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class PhotoAlbum(
    val id: String,
    val name: String,
    val photos: List<Uri>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryKApp()
        }
    }
}

suspend fun loadGallery(
    context: android.content.Context
): Pair<List<Uri>, List<PhotoAlbum>> =
    withContext(Dispatchers.IO) {

        val allPhotos = mutableListOf<Uri>()
        val albumMap = linkedMapOf<String, MutableList<Uri>>()
        val albumNames = mutableMapOf<String, String>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->

            val idColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media._ID
            )

            val bucketIdColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_ID
            )

            val bucketNameColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)

                val uri = ContentUris.withAppendedId(
                    collection,
                    id
                )

                val bucketId =
                    cursor.getString(bucketIdColumn) ?: "unknown"

                val bucketName =
                    cursor.getString(bucketNameColumn) ?: "Other"

                allPhotos.add(uri)

                albumMap.getOrPut(bucketId) {
                    mutableListOf()
                }.add(uri)

                albumNames[bucketId] = bucketName
            }
        }

        val albumList = albumMap.map { (id, albumPhotos) ->
            PhotoAlbum(
                id = id,
                name = albumNames[id] ?: "Other",
                photos = albumPhotos
            )
        }

        Pair(allPhotos, albumList)
    }

@Composable
fun GalleryKApp() {

    val context = LocalContext.current

    var photos by remember { mutableStateOf(listOf<Uri>()) }
    var albums by remember { mutableStateOf(listOf<PhotoAlbum>()) }

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf("Photos") }

    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }

    var refreshKey by remember { mutableIntStateOf(0) }

    val gridState = rememberLazyGridState()

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            permissionGranted = it
        }

    val deleteLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode == android.app.Activity.RESULT_OK) {
                selectedUris = emptySet()
                selectionMode = false
                refreshKey++
            }
        }

    LaunchedEffect(Unit) {
        permissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(permissionGranted, refreshKey) {
        if (permissionGranted) {
            loading = true

            val result = loadGallery(context)

            photos = result.first
            albums = result.second

            loading = false
        }
    }

    val currentAlbum = albums.find {
        it.id == selectedAlbumId
    }

    val displayedPhotos = currentAlbum?.photos ?: photos

    fun deleteSelectedPhotos() {

        val targets = selectedUris.toList()

        if (targets.isEmpty()) return

        try {
            if (Build.VERSION.SDK_INT >= 30) {

                val pendingIntent =
                    MediaStore.createDeleteRequest(
                        context.contentResolver,
                        targets
                    )

                deleteLauncher.launch(
                    IntentSenderRequest.Builder(
                        pendingIntent.intentSender
                    ).build()
                )

            } else {

                targets.forEach { uri ->
                    context.contentResolver.delete(uri, null, null)
                }

                selectedUris = emptySet()
                selectionMode = false
                refreshKey++
            }

        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Unable to delete photos",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    BackHandler(
        enabled = selectionMode ||
            selectedIndex >= 0 ||
            selectedAlbumId != null ||
            selectedTab == "Albums"
    ) {
        when {
            selectionMode -> {
                selectionMode = false
                selectedUris = emptySet()
            }

            selectedIndex >= 0 -> {
                selectedIndex = -1
            }

            selectedAlbumId != null -> {
                selectedAlbumId = null
            }

            selectedTab == "Albums" -> {
                selectedTab = "Photos"
            }
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101114)
        ) {

            if (
                selectedIndex >= 0 &&
                selectedIndex < displayedPhotos.size
            ) {

                FullScreenPhoto(
                    photos = displayedPhotos,
                    initialIndex = selectedIndex,
                    onBack = {
                        selectedIndex = -1
                    }
                )

            } else {

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 20.dp,
                                vertical = 18.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {

                            Text(
                                if (selectionMode) {
                                    "${selectedUris.size} Selected"
                                } else {
                                    "Gallery K"
                                },
                                color = Color.White,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                if (selectionMode) {
                                    "Select your photos"
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
                                    if (
                                        selectedUris.size ==
                                        displayedPhotos.size
                                    ) {
                                        selectedUris = emptySet()
                                    } else {
                                        selectedUris =
                                            displayedPhotos.toSet()
                                    }
                                }
                            ) {
                                Text(
                                    if (
                                        selectedUris.size ==
                                        displayedPhotos.size
                                    ) "Deselect All" else "Select All",
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (selectionMode) {

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement =
                                Arrangement.spacedBy(12.dp)
                        ) {

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = selectedUris.isNotEmpty(),
                                onClick = {
                                    deleteSelectedPhotos()
                                }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {

                            listOf("Photos", "Albums").forEach { tab ->

                                val active = selectedTab == tab

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (active) {
                                                Color(0xFF315C91)
                                            } else {
                                                Color(0xFF24262C)
                                            }
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
                                        permissionLauncher.launch(permission)
                                    }
                                ) {
                                    Text("Allow Photos")
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

                        displayedPhotos.isEmpty() -> {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No photos found",
                                    color = Color.White
                                )
                            }
                        }

                        else -> {

                            Text(
                                "${displayedPhotos.size} Photos",
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
                                modifier = Modifier.fillMaxSize()
                            ) {

                                itemsIndexed(
                                    displayedPhotos,
                                    key = { _, uri -> uri.toString() }
                                ) { index, photo ->

                                    PhotoThumbnail(
                                        uri = photo,
                                        selected = photo in selectedUris,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedUris =
                                                    if (photo in selectedUris) {
                                                        selectedUris - photo
                                                    } else {
                                                        selectedUris + photo
                                                    }

                                                if (selectedUris.isEmpty()) {
                                                    selectionMode = false
                                                }
                                            } else {
                                                selectedIndex = index
                                            }
                                        },
                                        onLongClick = {
                                            selectionMode = true
                                            selectedUris =
                                                selectedUris + photo
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
fun AlbumCard(
    album: PhotoAlbum,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF24262C))
            .clickable { onClick() }
            .padding(8.dp)
    ) {

        if (album.photos.isNotEmpty()) {
            PhotoThumbnail(
                uri = album.photos.first(),
                onClick = onClick
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
            "${album.photos.size} Photos",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnail(
    uri: Uri,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick
) {

    val context = LocalContext.current

    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(
                        uri,
                        android.util.Size(400, 400),
                        null
                    )
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    Box(
        modifier = Modifier
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
                contentDescription = "Photo thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
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

suspend fun loadFullPhoto(
    context: android.content.Context,
    uri: Uri
): Bitmap? = withContext(Dispatchers.IO) {

    try {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext null
        }

        val maxDimension = 4096
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)

        var sampleSize = 1

        while (largestSide / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

    } catch (e: Exception) {
        null
    }
}

@Composable
fun FullScreenPhoto(
    photos: List<Uri>,
    initialIndex: Int,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember {
        mutableIntStateOf(initialIndex)
    }

    var bitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var loading by remember {
        mutableStateOf(true)
    }

    var scale by remember {
        mutableFloatStateOf(1f)
    }

    var offsetX by remember {
        mutableFloatStateOf(0f)
    }

    var offsetY by remember {
        mutableFloatStateOf(0f)
    }

    var animating by remember {
        mutableStateOf(false)
    }

    val slide = remember {
        Animatable(0f)
    }

    LaunchedEffect(currentIndex) {
        loading = true

        bitmap = loadFullPhoto(
            context,
            photos[currentIndex]
        )

        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "‹",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.clickable {
                    onBack()
                }
            )

            Spacer(Modifier.width(20.dp))

            Text(
                "Photo ${currentIndex + 1} / ${photos.size}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(currentIndex, bitmap, animating) {

                    awaitEachGesture {

                        awaitFirstDown(requireUnconsumed = false)

                        var swipeX = 0f
                        var multiTouch = false

                        while (true) {

                            val event = awaitPointerEvent()

                            val pressed = event.changes.filter {
                                it.pressed
                            }

                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {

                                multiTouch = true

                                val first = pressed[0]
                                val second = pressed[1]

                                val oldDx =
                                    first.previousPosition.x -
                                        second.previousPosition.x

                                val oldDy =
                                    first.previousPosition.y -
                                        second.previousPosition.y

                                val newDx =
                                    first.position.x -
                                        second.position.x

                                val newDy =
                                    first.position.y -
                                        second.position.y

                                val oldDistance = sqrt(
                                    oldDx * oldDx + oldDy * oldDy
                                )

                                val newDistance = sqrt(
                                    newDx * newDx + newDy * newDy
                                )

                                if (oldDistance > 0f) {
                                    scale = (
                                        scale * newDistance / oldDistance
                                    ).coerceIn(1f, 5f)
                                }

                                offsetX += (
                                    first.position.x -
                                        first.previousPosition.x +
                                        second.position.x -
                                        second.previousPosition.x
                                ) / 2f

                                offsetY += (
                                    first.position.y -
                                        first.previousPosition.y +
                                        second.position.y -
                                        second.previousPosition.y
                                ) / 2f

                            } else if (pressed.size == 1) {

                                val change = pressed.first()

                                val dx =
                                    change.position.x -
                                        change.previousPosition.x

                                val dy =
                                    change.position.y -
                                        change.previousPosition.y

                                if (scale > 1.01f) {
                                    offsetX += dx
                                    offsetY += dy
                                } else if (!multiTouch) {
                                    swipeX += dx
                                }
                            }

                            val image = bitmap

                            if (image != null && scale > 1f) {

                                val fit = minOf(
                                    size.width.toFloat() / image.width,
                                    size.height.toFloat() / image.height
                                )

                                val imageWidth =
                                    image.width * fit * scale

                                val imageHeight =
                                    image.height * fit * scale

                                val maxX = (
                                    (imageWidth - size.width) / 2f
                                ).coerceAtLeast(0f)

                                val maxY = (
                                    (imageHeight - size.height) / 2f
                                ).coerceAtLeast(0f)

                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)

                            } else {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }

                            event.changes.forEach {
                                it.consume()
                            }
                        }

                        if (
                            !multiTouch &&
                            scale <= 1.01f &&
                            !animating &&
                            bitmap != null
                        ) {

                            val threshold = size.width * 0.18f

                            val target = when {
                                swipeX < -threshold &&
                                    currentIndex < photos.lastIndex ->
                                    currentIndex + 1

                                swipeX > threshold &&
                                    currentIndex > 0 ->
                                    currentIndex - 1

                                else -> currentIndex
                            }

                            if (target != currentIndex) {

                                val direction =
                                    if (target > currentIndex) 1f else -1f

                                val width = size.width.toFloat()

                                scope.launch {

                                    animating = true

                                    val nextBitmap = loadFullPhoto(
                                        context,
                                        photos[target]
                                    )

                                    if (nextBitmap != null) {

                                        slide.animateTo(
                                            -direction * width,
                                            animationSpec = tween(280)
                                        )

                                        currentIndex = target
                                        bitmap = nextBitmap

                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f

                                        slide.snapTo(direction * width)

                                        slide.animateTo(
                                            0f,
                                            animationSpec = tween(280)
                                        )
                                    }

                                    animating = false
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {

            when {

                loading -> {
                    CircularProgressIndicator(color = Color.White)
                }

                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Full resolution photo",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX + slide.value
                                translationY = offsetY
                            },
                        contentScale = ContentScale.Fit
                    )
                }

                else -> {
                    Text(
                        "Unable to load photo",
                        color = Color.White
                    )
                }
            }
        }
    }
}

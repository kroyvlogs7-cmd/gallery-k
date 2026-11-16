
package com.galleryk.app

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Intent
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import kotlin.math.abs

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

        val photos = mutableListOf<Uri>()
        val albumMap = linkedMapOf<String, MutableList<Uri>>()
        val names = mutableMapOf<String, String>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->

            val idCol = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media._ID
            )
            val bucketCol = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_ID
            )
            val nameCol = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(idCol)
                )

                val id = cursor.getString(bucketCol) ?: "other"
                val name = cursor.getString(nameCol) ?: "Other"

                photos.add(uri)
                albumMap.getOrPut(id) { mutableListOf() }.add(uri)
                names[id] = name
            }
        }

        val albums = albumMap.map { (id, list) ->
            PhotoAlbum(id, names[id] ?: "Other", list)
        }

        Pair(photos, albums)
    }

suspend fun loadBitmap(
    context: android.content.Context,
    uri: Uri,
    maxSize: Int = 4096
): Bitmap? = withContext(Dispatchers.IO) {

    try {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext null
        }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
        }

        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun GalleryKApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf(listOf<Uri>()) }
    var albums by remember { mutableStateOf(listOf<PhotoAlbum>()) }
    var selectedUris by remember { mutableStateOf(setOf<Uri>()) }

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var albumId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Photos") }

    var selectionMode by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

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
                refresh++
            }
        }

    LaunchedEffect(Unit) {
        permissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(permissionGranted, refresh) {
        if (permissionGranted) {
            loading = true
            val result = loadGallery(context)
            photos = result.first
            albums = result.second
            loading = false
        }
    }

    val currentAlbum = albums.find { it.id == albumId }
    val displayedPhotos = currentAlbum?.photos ?: photos

    fun deletePhotos(targets: List<Uri>) {
        if (targets.isEmpty()) return

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createDeleteRequest(
                    context.contentResolver,
                    targets
                )

                deleteLauncher.launch(
                    IntentSenderRequest.Builder(
                        request.intentSender
                    ).build()
                )
            } else {
                scope.launch(Dispatchers.IO) {
                    targets.forEach {
                        context.contentResolver.delete(it, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        refresh++
                        selectedUris = emptySet()
                        selectionMode = false
                    }
                }
            }
        } catch (_: Exception) {
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
            albumId != null ||
            tab == "Albums"
    ) {
        when {
            selectionMode -> {
                selectionMode = false
                selectedUris = emptySet()
            }
            selectedIndex >= 0 -> selectedIndex = -1
            albumId != null -> albumId = null
            else -> tab = "Photos"
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101114)
        ) {
            if (selectedIndex >= 0 && selectedIndex < displayedPhotos.size) {
                FullScreenPhoto(
                    photos = displayedPhotos,
                    initialIndex = selectedIndex,
                    onBack = { selectedIndex = -1 },
                    onDelete = { uri ->
                        deletePhotos(listOf(uri))
                        selectedIndex = -1
                    }
                )
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
                                if (selectionMode) "${selectedUris.size} Selected"
                                else "Gallery K",
                                color = Color.White,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (selectionMode) "Select your photos"
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
                                            displayedPhotos.isNotEmpty() &&
                                            selectedUris.containsAll(displayedPhotos)
                                        ) emptySet()
                                        else displayedPhotos.toSet()
                                }
                            ) {
                                Text(
                                    if (
                                        displayedPhotos.isNotEmpty() &&
                                        selectedUris.containsAll(displayedPhotos)
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = selectedUris.isNotEmpty(),
                                onClick = {
                                    deletePhotos(selectedUris.toList())
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
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("Photos", "Albums").forEach {
                                val active = tab == it

                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (active) Color(0xFF315C91)
                                            else Color(0xFF24262C)
                                        )
                                        .clickable {
                                            tab = it
                                            albumId = null
                                            selectionMode = false
                                            selectedUris = emptySet()
                                        }
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(it, color = Color.White)
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

                        tab == "Albums" && albumId == null -> {
                            Text(
                                "${albums.size} Albums",
                                color = Color.White,
                                modifier = Modifier.padding(16.dp)
                            )

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                itemsIndexed(
                                    albums,
                                    key = { _, album -> album.id }
                                ) { _, album ->
                                    AlbumCard(album) {
                                        albumId = album.id
                                    }
                                }
                            }
                        }

                        displayedPhotos.isEmpty() -> {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No photos found", color = Color.White)
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
                                                    if (photo in selectedUris)
                                                        selectedUris - photo
                                                    else
                                                        selectedUris + photo

                                                if (selectedUris.isEmpty()) {
                                                    selectionMode = false
                                                }
                                            } else {
                                                selectedIndex = index
                                            }
                                        },
                                        onLongClick = {
                                            selectionMode = true
                                            selectedUris = selectedUris + photo
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
        Modifier
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
        value = loadBitmap(context, uri, 500)
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
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )

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
                    modifier = Modifier
                        .padding(6.dp)
                        .background(
                            Color(0xFF2878D0),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
fun FullScreenPhoto(
    photos: List<Uri>,
    initialIndex: Int,
    onBack: () -> Unit,
    onDelete: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var index by remember { mutableIntStateOf(initialIndex) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var menuExpanded by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var wallpaperDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(index) {
        loading = true
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        bitmap = loadBitmap(context, photos[index])
        loading = false
    }

    fun sharePhoto() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, photos[index])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(intent, "Share Photo")
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                context,
                "Unable to share photo",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun setWallpaper(which: Int) {
        scope.launch {
            try {
                val manager = WallpaperManager.getInstance(context)
                val stream = context.contentResolver.openInputStream(
                    photos[index]
                ) ?: throw IllegalStateException("Cannot open photo")

                stream.use {
                    if (Build.VERSION.SDK_INT >= 24) {
                        manager.setStream(it, null, true, which)
                    } else {
                        manager.setStream(it)
                    }
                }

                android.widget.Toast.makeText(
                    context,
                    "Wallpaper set successfully",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Unable to set wallpaper",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.clickable { onBack() }
            )

            Spacer(Modifier.width(20.dp))

            Text(
                "Photo ${index + 1} / ${photos.size}",
                color = Color.White,
                modifier = Modifier.weight(1f)
            )

            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            menuExpanded = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⋮",
                        color = Color.White,
                        fontSize = 30.sp
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                    }
                ) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuExpanded = false
                            sharePhoto()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Photo Details") },
                        onClick = {
                            menuExpanded = false
                            detailsOpen = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Delete Photo") },
                        onClick = {
                            menuExpanded = false
                            deleteDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Set as Wallpaper") },
                        onClick = {
                            menuExpanded = false
                            wallpaperDialog = true
                        }
                    )
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(index, bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)

                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }

                        val maxOffsetX = size.width * (scale - 1f) / 2f
                        val maxOffsetY = size.height * (scale - 1f) / 2f

                        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                    }
                }
                .pointerInput(index) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(index, scale) {
                    var swipe = 0f

                    detectDragGestures(
                        onDragStart = {
                            swipe = 0f
                        },
                        onDrag = { change, amount ->
                            if (scale <= 1.01f) {
                                swipe += amount.x
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (abs(swipe) > size.width * 0.2f) {
                                if (swipe < 0 && index < photos.lastIndex) {
                                    index++
                                } else if (swipe > 0 && index > 0) {
                                    index--
                                }
                            }
                            swipe = 0f
                        },
                        onDragCancel = {
                            swipe = 0f
                        }
                    )
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
                        contentDescription = "Full Screen Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
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

        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ Previous",
                color = if (index > 0) Color.White else Color.Gray,
                modifier = Modifier.clickable {
                    if (index > 0) index--
                }
            )

            Text(
                "Next ›",
                color = if (index < photos.lastIndex) Color.White else Color.Gray,
                modifier = Modifier.clickable {
                    if (index < photos.lastIndex) index++
                }
            )
        }
    }

    if (detailsOpen) {
        val uri = photos[index]

        var name = "Unknown"
        var size = "Unknown"
        var date = "Unknown"
        var resolution = "Unknown"

        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: "Unknown"
                    size = "${cursor.getLong(1) / 1024} KB"

                    date = java.text.SimpleDateFormat(
                        "dd MMM yyyy HH:mm",
                        java.util.Locale.getDefault()
                    ).format(
                        java.util.Date(cursor.getLong(2) * 1000)
                    )

                    resolution =
                        "${cursor.getInt(3)} × ${cursor.getInt(4)}"
                }
            }
        } catch (_: Exception) {
        }

        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Photo Details") },
            text = {
                Column {
                    Text("Name: $name")
                    Text("Size: $size")
                    Text("Date: $date")
                    Text("Resolution: $resolution")
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsOpen = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete Photo?") },
            text = {
                Text("Are you sure you want to delete this photo?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialog = false
                        onDelete(photos[index])
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (wallpaperDialog) {
        AlertDialog(
            onDismissRequest = {
                wallpaperDialog = false
            },
            title = { Text("Set as Wallpaper") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            wallpaperDialog = false
                            setWallpaper(WallpaperManager.FLAG_SYSTEM)
                        }
                    ) {
                        Text("Home Screen")
                    }

                    TextButton(
                        onClick = {
                            wallpaperDialog = false
                            setWallpaper(WallpaperManager.FLAG_LOCK)
                        }
                    ) {
                        Text("Lock Screen")
                    }

                    TextButton(
                        onClick = {
                            wallpaperDialog = false
                            setWallpaper(
                                WallpaperManager.FLAG_SYSTEM or
                                    WallpaperManager.FLAG_LOCK
                            )
                        }
                    ) {
                        Text("Both")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wallpaperDialog = false
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}

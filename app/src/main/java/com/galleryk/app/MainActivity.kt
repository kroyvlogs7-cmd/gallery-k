
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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryKApp()
        }
    }
}

@Composable
fun GalleryKApp() {
    val context = LocalContext.current

    var photos by remember { mutableStateOf(listOf<Uri>()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        permissionGranted =
            ContextCompat.checkSelfPermission(
                context, permission
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            loading = true

            photos = withContext(Dispatchers.IO) {
                val result = mutableListOf<Uri>()
                val collection =
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val projection = arrayOf(
                    MediaStore.Images.Media._ID
                )

                val sortOrder =
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"

                context.contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Images.Media._ID
                        )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        result.add(
                            ContentUris.withAppendedId(
                                collection, id
                            )
                        )
                    }
                }
                result
            }

            loading = false
        }
    }

    BackHandler(enabled = selectedIndex >= 0) {
        selectedIndex = -1
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101114)
        ) {
            if (selectedIndex >= 0 && selectedIndex < photos.size) {
                FullScreenPhoto(
                    photos = photos,
                    initialIndex = selectedIndex,
                    onBack = { selectedIndex = -1 }
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 20.dp,
                            vertical = 20.dp
                        )
                    ) {
                        Text(
                            "Gallery K",
                            color = Color.White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Your memories, beautifully organized",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Text(
                        "Photos",
                        color = Color(0xFF8AB4F8),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            bottom = 10.dp
                        )
                    )

                    when {
                        !permissionGranted -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = {
                                        launcher.launch(permission)
                                    }
                                ) {
                                    Text("Allow Photos")
                                }
                            }
                        }

                        loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        photos.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
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
                                "${photos.size} Photos",
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
                                    photos,
                                    key = { _, uri -> uri.toString() }
                                ) { index, photo ->
                                    PhotoThumbnail(
                                        uri = photo,
                                        onClick = {
                                            selectedIndex = index
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
fun PhotoThumbnail(
    uri: Uri,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = uri
    ) {
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
            .clickable { onClick() },
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

        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            val maxDimension = 4096

            while (
                bounds.outWidth / (inSampleSize * 2) >= maxDimension ||
                bounds.outHeight / (inSampleSize * 2) >= maxDimension
            ) {
                inSampleSize *= 2
            }
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

    LaunchedEffect(currentIndex) {
        loading = true
        bitmap = null
        scale = 1f
        offsetX = 0f
        offsetY = 0f

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
                modifier = Modifier.clickable { onBack() }
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
                .pointerInput(currentIndex, scale) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)

                        if (scale <= 1.05f && zoom == 1f &&
                            abs(pan.x) > abs(pan.y)
                        ) {
                            offsetX += pan.x
                        } else {
                            scale = newScale
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        color = Color.White
                    )
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
                                translationX = offsetX
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Previous",
                color = if (currentIndex > 0) Color.White else Color.Gray,
                modifier = Modifier.clickable {
                    if (currentIndex > 0) currentIndex--
                }
            )

            Text(
                "Next",
                color = if (currentIndex < photos.lastIndex)
                    Color.White else Color.Gray,
                modifier = Modifier.clickable {
                    if (currentIndex < photos.lastIndex) currentIndex++
                }
            )
        }
    }
}

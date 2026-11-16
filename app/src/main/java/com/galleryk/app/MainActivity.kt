
package com.galleryk.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri

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
    var selectedPhoto by remember { mutableStateOf<Uri?>(null) }
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(
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
                    val idColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Images.Media._ID
                    )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        result.add(
                            ContentUris.withAppendedId(collection, id)
                        )
                    }
                }
                result
            }

            loading = false
        }
    }

    BackHandler(enabled = selectedPhoto != null) {
        selectedPhoto = null
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF101114)
        ) {
            if (selectedPhoto != null) {
                FullScreenPhoto(
                    uri = selectedPhoto!!,
                    onBack = { selectedPhoto = null }
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
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
                            "⋮",
                            color = Color.White,
                            fontSize = 28.sp
                        )
                    }

                    Text(
                        "Photos",
                        color = Color(0xFF8AB4F8),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            top = 8.dp,
                            bottom = 10.dp
                        )
                    )

                    when {
                        !permissionGranted -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "Allow access to your photos",
                                        color = Color.White
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            launcher.launch(permission)
                                        }
                                    ) {
                                        Text("Allow Photos")
                                    }
                                }
                            }
                        }

                        loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF8AB4F8)
                                )
                            }
                        }

                        photos.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No photos found",
                                    color = Color.LightGray
                                )
                            }
                        }

                        else -> {
                            Text(
                                "${photos.size} Photos",
                                color = Color.LightGray,
                                fontSize = 13.sp,
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
                                    items = photos,
                                    key = { it.toString() }
                                ) { photo ->
                                    PhotoThumbnail(
                                        uri = photo,
                                        onClick = {
                                            selectedPhoto = photo
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
                        android.util.Size(300, 300),
                        null
                    )
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
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
            .background(
                Color(0xFF292B30),
                RoundedCornerShape(5.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.Gray,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
fun FullScreenPhoto(
    uri: Uri,
    onBack: () -> Unit
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
                        android.util.Size(1600, 1600),
                        null
                    )
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
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
                "Photo",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 30.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Full screen photo",
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


package com.galleryk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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
    val context = androidx.compose.ui.platform.LocalContext.current
    var photos by remember { mutableStateOf(listOf<String>()) }
    var permissionGranted by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("Photos") }

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
            val result = mutableListOf<String>()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                uri, projection, null, null, sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media._ID
                )
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    result.add(
                        android.content.ContentUris.withAppendedId(uri, id).toString()
                    )
                }
            }
            photos = result
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101114))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Gallery K",
                    color = Color.White,
                    fontSize = 27.sp
                )
                Text("⋮", color = Color.White, fontSize = 28.sp)
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                listOf("Photos", "Albums").forEach { tab ->
                    Column(
                        modifier = Modifier.clickable {
                            selectedTab = tab
                        }
                    ) {
                        Text(
                            tab,
                            color = if (selectedTab == tab)
                                Color(0xFF8AB4F8) else Color.LightGray,
                            fontSize = 17.sp
                        )
                        if (selectedTab == tab) {
                            Spacer(
                                Modifier
                                    .padding(top = 7.dp)
                                    .width(48.dp)
                                    .height(3.dp)
                                    .background(Color(0xFF8AB4F8))
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (!permissionGranted) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Allow access to your photos",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { launcher.launch(permission) }) {
                        Text("Allow Photos")
                    }
                }
            } else if (selectedTab == "Photos") {
                Text(
                    "${photos.size} Photos",
                    color = Color.LightGray,
                    modifier = Modifier.padding(16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(photos) { photo ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .background(Color(0xFF292B30)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Photo",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Albums will appear here",
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

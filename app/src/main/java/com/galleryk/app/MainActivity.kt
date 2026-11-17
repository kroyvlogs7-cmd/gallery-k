
@Composable
private fun VideoPlayer(
    uri: Uri,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
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

    // Update video progress
    LaunchedEffect(exoPlayer) {
        while (true) {
            duration = exoPlayer.duration.coerceAtLeast(0L)

            if (!isScrubbing) {
                currentPosition =
                    exoPlayer.currentPosition.coerceAtLeast(0L)

                sliderPosition = currentPosition.toFloat()
            }

            delay(250)
        }
    }

    // Hide progress controls automatically
    LaunchedEffect(controlsVisible, exoPlayer.isPlaying, isScrubbing) {
        if (controlsVisible && exoPlayer.isPlaying && !isScrubbing) {
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
            exoPlayer.release()
        }
    }

    BackHandler { onBack() }

    fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
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
            modifier = Modifier.fillMaxSize()
        )

        // Tap and horizontal seek gestures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(exoPlayer) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                controlsVisible = true
                            } else {
                                exoPlayer.play()
                                controlsVisible = true
                            }
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
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragDistancePx += dragAmount

                            val dragDp = dragDistancePx / density
                            val timeChange = (dragDp * 100L).toLong()

                            var targetPosition =
                                (startPosition + timeChange).coerceAtLeast(0L)

                            val videoDuration = exoPlayer.duration

                            if (videoDuration > 0L) {
                                targetPosition =
                                    targetPosition.coerceAtMost(videoDuration)
                            }

                            exoPlayer.seekTo(targetPosition)
                            currentPosition = targetPosition
                            sliderPosition = targetPosition.toFloat()
                            controlsVisible = true
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

        // Video controls overlay
        if (controlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                // Play / Pause button
                Text(
                    if (exoPlayer.isPlaying) "Ⅱ" else "▶",
                    color = Color.White,
                    fontSize = 38.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x55000000))
                        .clickable {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                            controlsVisible = true
                        }
                        .padding(18.dp)
                )

                // Bottom progress bar and time
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
                                } else currentPosition
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
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
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

        // Brightness indicator
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

        // Smooth volume indicator
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

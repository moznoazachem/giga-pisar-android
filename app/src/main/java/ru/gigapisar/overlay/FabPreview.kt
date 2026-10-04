package ru.gigapisar.overlay

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The size being picked in the settings, while the slider is moving. The settings screen and
 * the accessibility service live in one process, so a plain flow carries it: the service shows
 * the real floating button at that size for a moment, over whatever is on screen.
 */
object FabPreview {
    val scale = MutableStateFlow<Float?>(null)
}

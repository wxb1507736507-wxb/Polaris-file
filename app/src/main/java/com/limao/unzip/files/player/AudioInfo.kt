/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 *
 * Transcribed and adapted from MaterialFiles
 * (https://github.com/zhanghai/MaterialFiles, GPL-3.0).
 */

package com.limao.unzip.files.player

import java.time.Duration

// @see com.android.providers.media.scan.ModernMediaScanner.scanItemAudio
// @see com.android.documentsui.inspector.MediaView.showAudioData
// @see https://github.com/GNOME/nautilus/blob/c73ad94a72f8e9a989b01858018de74182d17f0e/extensions/audio-video-properties/bacon-video-widget-properties.c#L89
class AudioInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val composer: String?,
    val discNumber: String?,
    val trackNumber: String?,
    val year: String?,
    val genre: String?,
    val duration: Duration?,
    val bitRate: Int?,
    val sampleRate: Int?
)

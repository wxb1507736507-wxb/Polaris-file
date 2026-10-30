/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 *
 * Transcribed and adapted from MaterialFiles
 * (https://github.com/zhanghai/MaterialFiles, GPL-3.0):
 * PathObserverLiveData/java.nio.Path dependencies replaced with a plain
 * String-path LiveData, and the util Stateful helpers are inlined here.
 */

package com.limao.unzip.files.player

import android.media.MediaMetadataRetriever
import android.os.AsyncTask
import android.os.Build
import androidx.lifecycle.LiveData
import com.limao.unzip.files.player.METADATA_KEY_SAMPLERATE
import com.limao.unzip.files.player.use
import java.time.Duration

sealed class Stateful<out T> {
    object Loading : Stateful<Nothing>()
    data class Success<T>(val value: T) : Stateful<T>()
    data class Failure<T>(val value: T?, val exception: Exception) : Stateful<T>()
}

fun MediaMetadataRetriever.extractMetadataNotBlank(keyCode: Int): String? =
    extractMetadata(keyCode)?.takeIf { it.isNotBlank() }

/** 音频元数据加载（MaterialFiles 的 AudioInfoLiveData 模式）。 */
class AudioInfoLiveData(private val path: String) : LiveData<Stateful<AudioInfo>>() {
    init {
        loadValue()
    }

    fun loadValue() {
        value = Stateful.Loading
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val audioInfo = MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(path)
                    val title = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_TITLE
                    )
                    val artist = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_ARTIST
                    )
                    val album = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_ALBUM
                    )
                    val albumArtist = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
                    )
                    val composer = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_COMPOSER
                    )
                    val discNumber = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER
                    )
                    val trackNumber = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                    )
                    val year = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_YEAR
                    )
                    val genre = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_GENRE
                    )
                    val duration = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.let { Duration.ofMillis(it) }
                    val bitRate = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_BITRATE
                    )?.toIntOrNull()
                    val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        retriever.extractMetadataNotBlank(
                            MediaMetadataRetriever::class.METADATA_KEY_SAMPLERATE
                        )?.toIntOrNull()
                    } else {
                        null
                    }
                    AudioInfo(
                        title, artist, album, albumArtist, composer, discNumber, trackNumber, year,
                        genre, duration, bitRate, sampleRate
                    )
                }
                Stateful.Success(audioInfo)
            } catch (e: Exception) {
                Stateful.Failure(null, e)
            }
            postValue(value)
        }
    }
}

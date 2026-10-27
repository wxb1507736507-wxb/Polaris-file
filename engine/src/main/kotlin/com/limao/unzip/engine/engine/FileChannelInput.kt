package com.limao.unzip.engine.engine

import com.limao.unzip.engine.api.SeekableInput
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * SeekableInput 的 FileChannel 实现（JVM 直用；Android 侧由
 * FileInputStream(pfd.fileDescriptor).channel 构造，见 app 模块）。
 */
class FileChannelInput private constructor(private val channel: FileChannel) : SeekableInput {

    override val sizeBytes: Long get() = channel.size()

    private val lock = Any()

    override fun pread(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        synchronized(lock) {
            channel.read(ByteBuffer.wrap(buffer, offset, length), position)
        }

    override fun close() {
        channel.close()
    }

    companion object {
        fun open(file: File): FileChannelInput =
            FileChannelInput(FileChannel.open(file.toPath(), StandardOpenOption.READ))

        fun of(channel: FileChannel): FileChannelInput = FileChannelInput(channel)
    }
}

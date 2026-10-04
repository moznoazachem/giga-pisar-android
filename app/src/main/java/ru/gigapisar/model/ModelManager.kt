package ru.gigapisar.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelManager(
    context: Context,
) {
    private val context = context.applicationContext

    private val modelDirectory: File =
        context.getDir(ModelConfig.DIRECTORY_NAME, Context.MODE_PRIVATE)

    val modelFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.MODEL_FILE_NAME,
            )

    val vocabFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.VOCAB_FILE_NAME,
            )

    private val readyFile: File
        get() =
            File(
                modelDirectory,
                ModelConfig.READY_FILE_NAME,
            )

    @Volatile
    private var isInstalledCached: Boolean? = null

    fun isInstalled(): Boolean {
        isInstalledCached?.let { return it }

        val installed =
            try {
                readyFile.exists() &&
                    modelFile.exists() &&
                    vocabFile.exists() &&
                    modelFile.length() == ModelConfig.EXPECTED_MODEL_SIZE
            } catch (_: Throwable) {
                false
            }

        if (installed) {
            isInstalledCached = true
        }

        return installed
    }

    suspend fun download(onProgress: suspend (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            isInstalledCached = null
            modelDirectory.mkdirs()

            // Old installation marker must never survive a failed update.
            readyFile.delete()

            val modelPart =
                File(
                    modelDirectory,
                    "${ModelConfig.MODEL_FILE_NAME}.part",
                )

            val vocabPart =
                File(
                    modelDirectory,
                    "${ModelConfig.VOCAB_FILE_NAME}.part",
                )

            modelFile.delete()
            vocabFile.delete()

            try {
                downloadFromMirrors(
                    urls = ModelConfig.MODEL_URLS,
                    destination = modelPart,
                    expectedSha256 = ModelConfig.MODEL_SHA256,
                    onProgress = onProgress,
                )

                require(modelPart.length() == ModelConfig.EXPECTED_MODEL_SIZE) {
                    "Неверный размер модели: ${modelPart.length()}"
                }

                moveFile(
                    modelPart,
                    modelFile,
                )

                downloadFromMirrors(
                    urls = ModelConfig.VOCAB_URLS,
                    destination = vocabPart,
                    expectedSha256 = ModelConfig.VOCAB_SHA256,
                    onProgress = {},
                )

                moveFile(
                    vocabPart,
                    vocabFile,
                )

                validateVocabulary()

                readyFile.writeText("ok")
                isInstalledCached = true
            } catch (error: Throwable) {
                isInstalledCached = false
                readyFile.delete()
                modelFile.delete()
                vocabFile.delete()
                modelPart.delete()
                vocabPart.delete()

                throw error
            }
        }
    }

    private fun validateVocabulary() {
        val lines =
            vocabFile
                .readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }

        require(lines.size == ModelConfig.VOCAB_SIZE) {
            "Неожиданный размер vocabulary: ${lines.size}, " +
                "ожидалось ${ModelConfig.VOCAB_SIZE}"
        }

        lines.forEachIndexed { index, line ->
            val separator =
                line.lastIndexOf(' ')

            require(separator > 0) {
                "Некорректная строка vocabulary #$index"
            }

            val token =
                line.substring(
                    0,
                    separator,
                )

            val id =
                line
                    .substring(
                        separator + 1,
                    ).trim()
                    .toIntOrNull()

            require(id == index) {
                "Некорректный ID vocabulary: " +
                    "строка=$index, id=$id"
            }

            if (index == ModelConfig.BLANK_ID) {
                require(token == "<blk>") {
                    "Последний токен должен быть <blk>, " +
                        "получен: $token"
                }
            }
        }
    }

    /** Tries each address in turn; the first (main) address's error is the one reported. */
    private suspend fun downloadFromMirrors(
        urls: List<String>,
        destination: File,
        expectedSha256: String?,
        onProgress: suspend (Int) -> Unit,
    ) {
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                destination.delete()
                downloadFile(url = url, destination = destination, expectedSha256 = expectedSha256, onProgress = onProgress)
                return
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (lastError == null) lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No download address")
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSha256: String?,
        onProgress: suspend (Int) -> Unit,
    ) {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"

            connection.connect()

            val responseCode =
                connection.responseCode

            require(responseCode in 200..299) {
                "HTTP $responseCode при загрузке $url"
            }

            val totalBytes =
                connection.contentLengthLong

            val digest =
                MessageDigest.getInstance("SHA-256")

            BufferedInputStream(
                connection.inputStream,
                64 * 1024,
            ).use { input ->

                FileOutputStream(destination).use { output ->

                    val buffer =
                        ByteArray(64 * 1024)

                    var downloaded = 0L
                    var lastProgress = -1

                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val count =
                            input.read(buffer)

                        if (count <= 0) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            count,
                        )

                        digest.update(
                            buffer,
                            0,
                            count,
                        )

                        downloaded += count

                        if (totalBytes > 0) {
                            val progress =
                                (
                                    downloaded * 100L /
                                        totalBytes
                                ).toInt()
                                    .coerceIn(0, 100)

                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            if (expectedSha256 != null) {
                val actualSha256 =
                    digest
                        .digest()
                        .joinToString("") {
                            "%02x".format(it)
                        }

                require(
                    actualSha256.equals(
                        expectedSha256,
                        ignoreCase = true,
                    ),
                ) {
                    "SHA-256 модели не совпадает. " +
                        "Ожидалось: $expectedSha256, " +
                        "получено: $actualSha256"
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun moveFile(
        source: File,
        destination: File,
    ) {
        require(source.exists()) {
            "Файл отсутствует: ${source.absolutePath}"
        }

        if (!source.renameTo(destination)) {
            source.copyTo(
                destination,
                overwrite = true,
            )

            source.delete()
        }
    }
}

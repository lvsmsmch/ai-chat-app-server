package com.lvsmsmch.aichat.utils

import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.path.createTempFile

data class UploadedImages(
    val originalUrl: String,
    val thumbnailUrl: String
)

/**
 * Хранение картинок на локальном диске сервера (вместо S3).
 * Файлы кладутся в IMAGES_DIR и раздаются Ktor-ом по /images/<file>
 * (см. configureRouting → staticFiles). Публичный URL строится из IMAGES_BASE_URL.
 */
object ImageServer {

    val imagesDir: File by lazy {
        File(System.getenv("IMAGES_DIR") ?: "/opt/chat/uploads").apply { mkdirs() }
    }

    private val baseUrl: String by lazy {
        (System.getenv("IMAGES_BASE_URL") ?: "http://161.35.210.53:8080/images").trimEnd('/')
    }

    /** Целевой размер файла: пережимаем всё, что тяжелее (~500КБ), к ~300-400КБ. */
    private const val TARGET_BYTES = 500_000
    private const val MAX_SIDE = 1024

    suspend fun uploadImageOnServer(image: File): UploadedImages {
        logger.debug("image name: ${image.name}, size: ${image.length()}")

        detectImageFormat(image) // валидация: только JPEG/PNG

        val baseUuid = UUID.randomUUID().toString()
        val fileName = "$baseUuid.jpg"

        var processed: File? = null
        try {
            // Всегда прогоняем через рекомпрессию: PNG→JPEG, даунскейл до 1024,
            // качество подбирается до попадания в TARGET_BYTES
            processed = recompress(image)

            processed.copyTo(File(imagesDir, fileName), overwrite = true)

            val url = "$baseUrl/$fileName"
            logger.debug("Stored image: $url (${File(imagesDir, fileName).length()} bytes)")

            // Миниатюры больше не используем: thumbnail = оригинал
            return UploadedImages(url, url)

        } catch (e: Exception) {
            logger.error("Failed to process and store image", e)
            throw Exception("Image processing failed: ${e.message}", e)
        } finally {
            if (processed != image && processed?.exists() == true) {
                processed.delete()
            }
        }
    }

    /** PNG/JPEG → JPEG ≤1024px и ~≤500КБ (ступенчатое снижение качества). */
    private fun recompress(input: File): File {
        val src = ImageIO.read(input) ?: throw Exception("Could not read image file")

        val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
        val (w, h) = if (scale < 1f)
            (src.width * scale).toInt().coerceAtLeast(1) to (src.height * scale).toInt().coerceAtLeast(1)
        else src.width to src.height

        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        canvas.graphics.apply {
            drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }

        var quality = 0.85f
        var out = createTempFile("processed", ".jpg").toFile()
        while (true) {
            writeJpeg(canvas, out, quality)
            if (out.length() <= TARGET_BYTES || quality <= 0.45f) break
            quality -= 0.1f
        }
        return out
    }

    private fun convertToJpeg(inputFile: File, quality: Float): File {
        try {
            val image = ImageIO.read(inputFile)
                ?: throw Exception("Could not read image file")

            val jpegImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            jpegImage.graphics.apply {
                drawImage(image, 0, 0, null)
                dispose()
            }

            val tempFile = createTempFile("converted", ".jpg").toFile()
            writeJpeg(jpegImage, tempFile, quality)
            return tempFile
        } catch (e: Exception) {
            throw Exception("Failed to convert PNG to JPEG: ${e.message}", e)
        }
    }

    private fun writeJpeg(image: BufferedImage, target: File, quality: Float) {
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        val writer = writers.next()
        val writeParam = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        FileOutputStream(target).use { fos ->
            writer.output = ImageIO.createImageOutputStream(fos)
            writer.write(null, javax.imageio.IIOImage(image, null, null), writeParam)
        }
        writer.dispose()
    }

    private fun detectImageFormat(file: File): String {
        val bytes = file.readBytes().take(4).toByteArray()

        return when {
            bytes.size >= 2 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() -> "jpg"

            bytes.size >= 4 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() -> "png"

            else -> throw BadRequestException("Unsupported image format. Only JPEG and PNG are allowed.")
        }
    }
}

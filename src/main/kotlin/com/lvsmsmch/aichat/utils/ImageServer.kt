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

    suspend fun uploadImageOnServer(image: File): UploadedImages {
        logger.debug("image name: ${image.name}")

        val detectedFormat = detectImageFormat(image)
        logger.debug("detected format: $detectedFormat")

        val baseUuid = UUID.randomUUID().toString()
        val originalFileName = "$baseUuid.jpg"
        val thumbnailFileName = "${baseUuid}_thumb.jpg"

        var originalFile: File? = null
        var thumbnailFile: File? = null

        try {
            originalFile = if (detectedFormat == "png") {
                convertToJpeg(image, 0.85f)
            } else {
                image
            }

            thumbnailFile = createThumbnail(originalFile, 256, 256, 0.85f)

            originalFile.copyTo(File(imagesDir, originalFileName), overwrite = true)
            thumbnailFile.copyTo(File(imagesDir, thumbnailFileName), overwrite = true)

            val originalUrl = "$baseUrl/$originalFileName"
            val thumbnailUrl = "$baseUrl/$thumbnailFileName"

            logger.debug("Original URL: $originalUrl")
            logger.debug("Thumbnail URL: $thumbnailUrl")

            return UploadedImages(originalUrl, thumbnailUrl)

        } catch (e: Exception) {
            logger.error("Failed to process and store image", e)
            throw Exception("Image processing failed: ${e.message}", e)
        } finally {
            if (originalFile != image && originalFile?.exists() == true) {
                originalFile.delete()
            }
            thumbnailFile?.delete()
        }
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

    private fun createThumbnail(inputFile: File, width: Int, height: Int, quality: Float): File {
        try {
            val originalImage = ImageIO.read(inputFile)
                ?: throw Exception("Could not read image file for thumbnail")

            val thumbnail = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            thumbnail.graphics.apply {
                // SMOOTH — без «лесенки», миниатюры показываются и на плитках
                drawImage(originalImage.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
                dispose()
            }

            val tempFile = createTempFile("thumbnail", ".jpg").toFile()
            writeJpeg(thumbnail, tempFile, quality)
            return tempFile
        } catch (e: Exception) {
            throw Exception("Failed to create thumbnail: ${e.message}", e)
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

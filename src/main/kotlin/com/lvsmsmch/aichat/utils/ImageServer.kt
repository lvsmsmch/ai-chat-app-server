package com.lvsmsmch.aichat.utils

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetUrlRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.awt.RenderingHints
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

object ImageServer {

    suspend fun uploadImageOnServer(image: File): UploadedImages {
        val accessKey = System.getenv("AWS_ACCESS_KEY") ?: throw Exception("Missing AWS_ACCESS_KEY key")
        val secretKey = System.getenv("AWS_SECRET_KEY") ?: throw Exception("Missing AWS_SECRET_KEY key")
        val bucketName = System.getenv("AWS_S3_BUCKET_NAME") ?: throw Exception("Missing AWS_S3_BUCKET_NAME key")
        val region = System.getenv("AWS_REGION") ?: throw Exception("Missing AWS_REGION key")

        logger.debug("image name: ${image.name}")
        logger.debug("image ext: ${image.extension}")

        val detectedFormat = detectImageFormat(image)
        logger.debug("detected format: $detectedFormat")

        val baseUuid = UUID.randomUUID().toString()
        val originalFileName = "$baseUuid.jpg"
        val thumbnailFileName = "${baseUuid}_thumb.jpg"

        val credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)
        )

        val s3Client = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider)
            .build()

        var originalFile: File? = null
        var thumbnailFile: File? = null

        try {
            originalFile = if (detectedFormat == "png") {
                convertToJpeg(image, 0.85f)
            } else {
                image
            }

            thumbnailFile = createThumbnail(originalFile, 100, 100, 0.85f)

            val originalUrl = uploadToS3(s3Client, originalFile, bucketName, originalFileName)
            val thumbnailUrl = uploadToS3(s3Client, thumbnailFile, bucketName, thumbnailFileName)

            logger.debug("Original URL: $originalUrl")
            logger.debug("Thumbnail URL: $thumbnailUrl")

            return UploadedImages(originalUrl, thumbnailUrl)

        } catch (e: Exception) {
            logger.error("Failed to process and upload image", e)
            throw Exception("Image processing failed: ${e.message}", e)
        } finally {
            if (originalFile != image && originalFile?.exists() == true) {
                originalFile.delete()
            }
            thumbnailFile?.delete()
            s3Client.close()
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

            val writers = ImageIO.getImageWritersByFormatName("jpg")
            val writer = writers.next()
            val writeParam = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }

            FileOutputStream(tempFile).use { fos ->
                writer.output = ImageIO.createImageOutputStream(fos)
                writer.write(null, javax.imageio.IIOImage(jpegImage, null, null), writeParam)
            }
            writer.dispose()

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
                drawImage(originalImage.getScaledInstance(width, height, java.awt.Image.SCALE_FAST), 0, 0, null)
                dispose()
            }

            val tempFile = createTempFile("thumbnail", ".jpg").toFile()

            val writers = ImageIO.getImageWritersByFormatName("jpg")
            val writer = writers.next()
            val writeParam = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }

            FileOutputStream(tempFile).use { fos ->
                writer.output = ImageIO.createImageOutputStream(fos)
                writer.write(null, javax.imageio.IIOImage(thumbnail, null, null), writeParam)
            }
            writer.dispose()

            return tempFile
        } catch (e: Exception) {
            throw Exception("Failed to create thumbnail: ${e.message}", e)
        }
    }

    private fun uploadToS3(s3Client: S3Client, file: File, bucketName: String, fileName: String): String {
        try {
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType("image/jpeg")
                .build()

            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file))

            val getUrlRequest = GetUrlRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build()

            return s3Client.utilities().getUrl(getUrlRequest).toString()
        } catch (e: Exception) {
            throw Exception("Failed to upload to S3: ${e.message}", e)
        }
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
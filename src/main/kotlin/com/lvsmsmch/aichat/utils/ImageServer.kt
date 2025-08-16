package com.lvsmsmch.aichat.utils


import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetUrlRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.util.*


object ImageServer {

    /**
     * Uploads an image file to Amazon S3 and returns the URL of the uploaded image.
     *
     * @param image The image file to upload
     * @return The URL of the uploaded image
     */
    suspend fun uploadImageOnServer(image: File): String {
        val accessKey = System.getenv("AWS_ACCESS_KEY") ?: throw Exception("Missing AWS_ACCESS_KEY key")
        val secretKey = System.getenv("AWS_SECRET_KEY") ?: throw Exception("Missing AWS_SECRET_KEY key")
        val bucketName = System.getenv("AWS_S3_BUCKET_NAME") ?: throw Exception("Missing AWS_S3_BUCKET_NAME key")
        val region = System.getenv("AWS_REGION") ?: throw Exception("Missing AWS_REGION key")

        logger.debug("image name: $${image.name}")
        logger.debug("image ext: $${image.extension}")
        logger.debug("detected ext: $${detectImageFormat(image)}")
        // Generate a unique file name for the image
//        val fileExtension = when {
//            image.name.endsWith(".png", true) -> "png"
//            image.name.endsWith(".jpg", true) || image.name.endsWith(".jpeg", true) -> "jpg"
//            else -> throw BadRequestException("Image file extension must be a png or jpg")
//        }
        val fileExtension = detectImageFormat(image)
        val uniqueFileName = UUID.randomUUID().toString() + ".$fileExtension"

        // Configure S3 client
        val credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)
        )

        val s3ClientBuilder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider)

        val s3Client = s3ClientBuilder.build()

        s3Client.use {
            // Upload the file to S3
            val contentType = when (fileExtension) {
                "png" -> "image/png"
                "jpg" -> "image/jpeg"
                else -> "image/jpeg"
            }

            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(uniqueFileName)
                .contentType(contentType)
                .build()

            it.putObject(putObjectRequest, RequestBody.fromFile(image))

            val getUrlRequest = GetUrlRequest.builder()
                .bucket(bucketName)
                .key(uniqueFileName)
                .build()

            val finalUrl = s3Client.utilities().getUrl(getUrlRequest).toString()

            logger.debug("finalUrl: ${finalUrl}")

            return finalUrl
        }
    }

    private fun detectImageFormat(file: File): String {
        val bytes = file.readBytes().take(4).toByteArray()

        return when {
            // JPEG: FF D8
            bytes.size >= 2 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() -> "jpg"

            // PNG: 89 50 4E 47
            bytes.size >= 4 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() -> "png"

            else -> throw BadRequestException("Unsupported image format. Only JPEG and PNG are allowed.")
        }
    }
}
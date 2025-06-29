package com.lvsmsmch.aichat.utils


import kotlinx.coroutines.delay
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.util.UUID
import kotlin.random.Random


object ImageServer {

    /**
     * Uploads an image file to Amazon S3 and returns the URL of the uploaded image.
     *
     * @param image The image file to upload
     * @return The URL of the uploaded image
     */
    suspend fun uploadImageOnServer(image: File): String {


        // test configuration >>>>>>>>>
        delay(2000L + Random.nextLong(1000L)) // 2-3 секунды
        return "https://picsum.photos/800/600?random=${Random.nextInt(100_000)}"
        // test configuration >>>>>>


        // S3 configuration

        val bucketName = loadConfig().getProperty("AWS_S3_BUCKET_NAME")
        val region = loadConfig().getProperty("AWS_REGION")
        val accessKey = loadConfig().getProperty("AWS_ACCESS_KEY")
        val secretKey = loadConfig().getProperty("AWS_SECRET_KEY")

        // Generate a unique file name for the image
        val uniqueFileName = UUID.randomUUID().toString() + ".jpg"

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
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(uniqueFileName)
                .contentType("image/jpeg")
                .build()

            it.putObject(
                putObjectRequest,
                RequestBody.fromFile(image)
            )

            return "https://$bucketName.s3.$region.amazonaws.com/$uniqueFileName"
        }
    }
}
package com.suguna.rtc.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

object CloudflareMusicUploader {

    private const val ACCESS_KEY = "aa7abec8bf2caaff472ae54a80416c9e"
    private const val SECRET_KEY = "23e4c23b1da2344934c0041285e70f6fa2952179fe74b9e3aaa0bb01d572109b"
    private const val ENDPOINT = "https://b633fdf2e6c07c2c67dc1a447f3dabe9.r2.cloudflarestorage.com"
    private const val BUCKET_NAME = "suguna-live-music"
    private const val PUBLIC_URL = "https://pub-0d3efdbd319348ecb88285fb79ec925d.r2.dev"

    suspend fun uploadMusic(context: Context, localUri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val credentials = BasicAWSCredentials(ACCESS_KEY, SECRET_KEY)
                val clientConfig = com.amazonaws.ClientConfiguration().apply {
                    signerOverride = "AWSS3V4SignerType"
                }
                val s3Client = AmazonS3Client(credentials, clientConfig).apply {
                    setRegion(com.amazonaws.regions.Region.getRegion(com.amazonaws.regions.Regions.US_EAST_1))
                    setEndpoint(ENDPOINT)
                    // CRITICAL FOR CLOUDFLARE R2: Path Style, No Chunking, No Payload Signing
                    setS3ClientOptions(
                        com.amazonaws.services.s3.S3ClientOptions.builder()
                            .setPathStyleAccess(true)
                            .disableChunkedEncoding()
                            .setPayloadSigningEnabled(false)
                            .build()
                    )
                }
                
                // Write the MediaStore URI to a temporary file
                val tempFile = java.io.File(context.cacheDir, "temp_upload_audio.mp3")
                val inputStreamLocal = context.contentResolver.openInputStream(localUri)
                    ?: return@withContext null
                
                tempFile.outputStream().use { output ->
                    inputStreamLocal.copyTo(output)
                }
                inputStreamLocal.close()

                // Generate a Presigned URL for PUT
                val expiration = java.util.Date(System.currentTimeMillis() + 1000 * 60 * 15) // 15 mins
                val fileName = "audio_${UUID.randomUUID()}.mp3"
                val presignedReq = com.amazonaws.services.s3.model.GeneratePresignedUrlRequest(
                    BUCKET_NAME, fileName, com.amazonaws.HttpMethod.PUT
                ).apply {
                    this.expiration = expiration
                    contentType = "audio/mpeg"
                }
                val presignedUrl = s3Client.generatePresignedUrl(presignedReq)

                // COMPLETELY BYPASS AWS SDK's PutObject method!
                // We use standard Android HttpURLConnection to upload standard bytes.
                // This guarantees ZERO chunking, ZERO payload signature headers, just pure bytes.
                val connection = presignedUrl.openConnection() as java.net.HttpURLConnection
                connection.doOutput = true
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "audio/mpeg")
                connection.setRequestProperty("Content-Length", tempFile.length().toString())
                
                java.io.FileInputStream(tempFile).use { fileStream ->
                    connection.outputStream.use { outStream ->
                        fileStream.copyTo(outStream)
                    }
                }
                
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val errorString = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    throw Exception("S3 HTTP Error: $errorString")
                }
                
                // Clean up temp file
                if (tempFile.exists()) tempFile.delete()
                
                return@withContext "$PUBLIC_URL/$fileName"
            } catch (e: Exception) {
                Log.e("Cloudflare Music Uploader", "Upload Failed", e)
                e.printStackTrace()
                return@withContext "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
}

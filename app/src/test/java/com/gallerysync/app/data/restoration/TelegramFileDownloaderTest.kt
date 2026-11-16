package com.gallerysync.app.data.restoration

import com.gallerysync.app.api.TelegramCatalogApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramFileDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createMockClient(
        getFileStatusCode: Int = 200,
        getFileJson: String = """{"ok":true,"result":{"file_id":"fid_123","file_unique_id":"uniq_123","file_size":1024,"file_path":"videos/chunk_0.mp4"}}""",
        downloadStatusCode: Int = 200,
        downloadBodyBytes: ByteArray = ByteArray(1024),
        downloadBodyString: String? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()

                if (url.contains("getFile")) {
                    val body = getFileJson.toResponseBody("application/json".toMediaType())
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(getFileStatusCode)
                        .message("OK")
                        .body(body)
                        .build()
                } else {
                    val body = if (downloadBodyString != null) {
                        downloadBodyString.toResponseBody("application/json".toMediaType())
                    } else {
                        downloadBodyBytes.toResponseBody("application/octet-stream".toMediaType())
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(downloadStatusCode)
                        .message("OK")
                        .body(body)
                        .build()
                }
            }
            .build()
    }

    @Test
    fun testDownloadToFile_success() = runBlocking {
        val payload = Random.nextBytes(65536 * 2) // 128 KiB
        val expectedSha = sha256(payload)
        val getFileJson = """{"ok":true,"result":{"file_id":"f001","file_size":${payload.size},"file_path":"test_chunk.bin"}}"""

        val mockClient = createMockClient(
            getFileJson = getFileJson,
            downloadBodyBytes = payload
        )
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_chunk.bin")
        var progressReported = false

        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f001",
            destinationFile = destinationFile,
            expectedSizeBytes = payload.size.toLong(),
            expectedSha256 = expectedSha,
            onProgress = { bytesRead, total ->
                if (bytesRead > 0) progressReported = true
            }
        )

        assertTrue(result is TelegramDownloadResult.Success)
        val success = result as TelegramDownloadResult.Success
        assertEquals(payload.size.toLong(), success.bytesRead)
        assertEquals(expectedSha, success.sha256)
        assertTrue(destinationFile.exists())
        assertArrayEquals(payload, destinationFile.readBytes())
        assertTrue(progressReported)
    }

    @Test
    fun testDownloadToFile_failsWhenFileSizeExceeds20MbLimit() = runBlocking {
        val oversized = 25_000_000L // 25 MB
        val getFileJson = """{"ok":true,"result":{"file_id":"f_huge","file_size":$oversized,"file_path":"huge_video.mp4"}}"""

        val mockClient = createMockClient(getFileJson = getFileJson)
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_huge.mp4")

        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f_huge",
            destinationFile = destinationFile
        )

        assertTrue(result is TelegramDownloadResult.InvalidResponse)
        val err = (result as TelegramDownloadResult.InvalidResponse).reason
        assertTrue(err.contains("20000000 bytes"))
        assertFalse(destinationFile.exists())
    }

    @Test
    fun testDownloadToFile_failsOnChecksumMismatch_cleansUpFile() = runBlocking {
        val payload = Random.nextBytes(1000)
        val getFileJson = """{"ok":true,"result":{"file_id":"f_corrupt","file_size":1000,"file_path":"chunk.bin"}}"""

        val mockClient = createMockClient(
            getFileJson = getFileJson,
            downloadBodyBytes = payload
        )
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_corrupt.bin")

        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f_corrupt",
            destinationFile = destinationFile,
            expectedSizeBytes = 1000L,
            expectedSha256 = "wrong_sha256_checksum"
        )

        assertTrue(result is TelegramDownloadResult.IntegrityMismatch)
        assertFalse(destinationFile.exists()) // Partial / corrupt files must be discarded
        assertFalse(File(destinationFile.parentFile, "${destinationFile.name}.part").exists())
    }

    @Test
    fun testDownloadToFile_failsOnByteCountMismatch() = runBlocking {
        val payload = Random.nextBytes(500)
        val getFileJson = """{"ok":true,"result":{"file_id":"f_short","file_size":500,"file_path":"chunk.bin"}}"""

        val mockClient = createMockClient(
            getFileJson = getFileJson,
            downloadBodyBytes = payload
        )
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_short.bin")

        // Expected 1000 bytes, but remote only sends 500
        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f_short",
            destinationFile = destinationFile,
            expectedSizeBytes = 1000L
        )

        assertTrue(result is TelegramDownloadResult.IntegrityMismatch)
        assertFalse(destinationFile.exists())
    }

    @Test
    fun testDownloadToFile_handlesHttp429RateLimit() = runBlocking {
        val rateLimitJson = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":35}}"""
        val mockClient = createMockClient(
            downloadStatusCode = 429,
            downloadBodyString = rateLimitJson
        )
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_429.bin")

        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f_rate_limited",
            destinationFile = destinationFile
        )

        assertTrue(result is TelegramDownloadResult.RateLimited)
        val rl = result as TelegramDownloadResult.RateLimited
        assertEquals(35, rl.retryAfterSeconds)
    }

    @Test
    fun testDownloadToFile_handlesHttp401Unauthorized() = runBlocking {
        val mockClient = createMockClient(
            getFileStatusCode = 401,
            getFileJson = """{"ok":false,"error_code":401,"description":"Unauthorized"}"""
        )
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_401.bin")

        val result = downloader.downloadToFile(
            botToken = "999:bad_token",
            fileId = "f_401",
            destinationFile = destinationFile
        )

        assertTrue(result is TelegramDownloadResult.Unauthorized)
        assertEquals(401, (result as TelegramDownloadResult.Unauthorized).code)
    }

    @Test
    fun testDownloadToFile_cooperativeCancellation() = runBlocking {
        val mockClient = createMockClient(downloadBodyBytes = Random.nextBytes(100_000))
        val catalogApi = TelegramCatalogApi(mockClient)
        val downloader = TelegramFileDownloader(mockClient, catalogApi)

        val destinationFile = File(tempFolder.root, "dest_cancelled.bin")

        val result = downloader.downloadToFile(
            botToken = "999:fake_token",
            fileId = "f_cancel",
            destinationFile = destinationFile,
            isCancelled = { true }
        )

        assertTrue(result is TelegramDownloadResult.Cancelled)
        assertFalse(destinationFile.exists())
    }

    @Test
    fun testCredentialRedaction_sanitizesTokensAndUrls() {
        val rawMessage = "Failed download from https://api.telegram.org/file/bot987654321:AAHkdjasdj98123kjh/chunk_1.bin with bot987654321:AAHkdjasdj98123kjh"
        val redacted = TelegramDownloadException.redactCredentials(rawMessage)

        assertFalse(redacted.contains("bot987654321:AAHkdjasdj98123kjh"))
        assertTrue(redacted.contains("bot[REDACTED]"))
        assertTrue(redacted.contains("https://api.telegram.org/file/bot[REDACTED]"))
    }
}

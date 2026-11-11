package datadog.buildlogic.smoketest

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Caches verified Gradle installations independently from the user home of a nested daemon. */
internal class GradleDistributionCache(
  private val cacheDirectory: File,
  private val connectionFactory: (URI) -> URLConnection = { it.toURL().openConnection() },
  private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

  fun getInstallation(distributionUri: URI, gradleVersion: String): File {
    val key = sha256(distributionUri.toString()).take(KEY_LENGTH)
    val entryDirectory = cacheDirectory.resolve("${safeFileName(gradleVersion)}-$key")
    val lockFile = cacheDirectory.resolve("$key.lock")
    cacheDirectory.mkdirs()

    return synchronized(
      "datadog.buildlogic.smoketest.GradleDistributionCache:${lockFile.canonicalPath}".intern(),
    ) {
      FileChannel.open(
        lockFile.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
      ).use { channel ->
        val fileLock = channel.lock()
        try {
          val installation = entryDirectory.resolve("installation")
          val marker = entryDirectory.resolve("READY")
          if (isReady(marker, installation, distributionUri, gradleVersion)) {
            logger.lifecycle("Using cached Gradle $gradleVersion installation at $installation")
            installation
          } else {
            entryDirectory.deleteRecursively()
            entryDirectory.mkdirs()
            try {
              logger.lifecycle("Downloading Gradle $gradleVersion distribution from $distributionUri")
              val expectedChecksum = downloadChecksum(distributionUri)
              val archive = entryDirectory.resolve("distribution.zip.tmp-${UUID.randomUUID()}")
              try {
                downloadTo(distributionUri, archive, "Gradle $gradleVersion distribution")
                verifyChecksum(archive, expectedChecksum, distributionUri)
                unpack(archive, entryDirectory, installation)
              } finally {
                archive.delete()
              }
              marker.writeText(
                "uri=$distributionUri\nversion=$gradleVersion\nsha256=$expectedChecksum\n",
              )
              logger.lifecycle("Cached Gradle $gradleVersion installation at $installation")
              installation
            } catch (e: Exception) {
              entryDirectory.deleteRecursively()
              throw e
            }
          }
        } finally {
          fileLock.release()
        }
      }
    }
  }

  private fun downloadChecksum(distributionUri: URI): String {
    val checksumUri = URI.create("$distributionUri.sha256")
    val checksumText = downloadText(checksumUri, "Gradle distribution checksum")
    return CHECKSUM_PATTERN.find(checksumText)?.groupValues?.get(1)?.lowercase()
      ?: throw GradleException("No SHA-256 checksum was returned for $distributionUri")
  }

  private fun downloadText(uri: URI, description: String): String {
    val file = File.createTempFile("gradle-distribution-checksum-", ".tmp", cacheDirectory)
    return try {
      downloadTo(uri, file, description)
      file.readText().trim()
    } finally {
      file.delete()
    }
  }

  private fun downloadTo(uri: URI, destination: File, description: String) {
    var lastFailure: Exception? = null
    repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
      var connection: URLConnection? = null
      try {
        connection = connectionFactory(uri)
        val http = connection as? HttpURLConnection
        http?.apply {
          instanceFollowRedirects = true
          connectTimeout = CONNECT_TIMEOUT_MILLIS
          readTimeout = READ_TIMEOUT_MILLIS
          if (responseCode !in 200..299) {
            throw DownloadFailure(responseCode)
          }
        }
        connection.getInputStream().use { input ->
          Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return
      } catch (e: DownloadFailure) {
        lastFailure = e
        if (!e.isTransient || attempt == MAX_DOWNLOAD_ATTEMPTS - 1) {
          throw downloadException(uri, description, attempt + 1, e)
        }
      } catch (e: IOException) {
        lastFailure = e
        if (attempt == MAX_DOWNLOAD_ATTEMPTS - 1) {
          throw downloadException(uri, description, attempt + 1, e)
        }
      } finally {
        (connection as? HttpURLConnection)?.disconnect()
      }
      try {
        sleep(RETRY_DELAY_MILLIS * (1L shl attempt))
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw downloadException(uri, description, attempt + 1, e)
      }
    }
    throw downloadException(uri, description, MAX_DOWNLOAD_ATTEMPTS, lastFailure)
  }

  private fun verifyChecksum(archive: File, expected: String, distributionUri: URI) {
    val actual = archive.inputStream().use { input ->
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(BUFFER_SIZE)
      var read = input.read(buffer)
      while (read != -1) {
        digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    }
    if (actual != expected) {
      throw GradleException(
        "SHA-256 checksum mismatch for $distributionUri: expected $expected but was $actual",
      )
    }
  }

  private fun unpack(archive: File, entryDirectory: File, installation: File) {
    val staging = entryDirectory.resolve("installation.tmp-${UUID.randomUUID()}")
    staging.mkdirs()
    try {
      val stagingPath = staging.toPath().toAbsolutePath().normalize()
      ZipFile(archive).use { zip ->
        zip.entries().asSequence().forEach { entry ->
          val output = staging.resolve(entry.name).toPath().toAbsolutePath().normalize()
          if (!output.startsWith(stagingPath)) {
            throw GradleException("Gradle distribution contains an unsafe ZIP entry: ${entry.name}")
          }
          if (entry.isDirectory) {
            Files.createDirectories(output)
          } else {
            Files.createDirectories(output.parent)
            zip.getInputStream(entry).use { input ->
              Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
            }
          }
        }
      }

      val children = staging.listFiles()?.toList().orEmpty()
      val extractedRoot = children.singleOrNull()?.takeIf { it.isDirectory } ?: staging
      Files.move(extractedRoot.toPath(), installation.toPath())
      installation.resolve("bin/gradle").setExecutable(true)
      installation.resolve("bin/gradle.bat").setExecutable(true)
      if (!isGradleInstallation(installation)) {
        throw GradleException("Gradle distribution did not contain a usable installation")
      }
    } finally {
      staging.deleteRecursively()
    }
  }

  private fun isReady(
    marker: File,
    installation: File,
    distributionUri: URI,
    gradleVersion: String,
  ): Boolean {
    if (!marker.isFile || !installation.isDirectory || !isGradleInstallation(installation)) {
      return false
    }
    val metadata = marker.readText()
    return metadata.contains("uri=$distributionUri\n") &&
      metadata.contains("version=$gradleVersion\n") &&
      metadata.contains("sha256=[0-9a-f]{64}".toRegex())
  }

  private fun isGradleInstallation(directory: File): Boolean =
    directory.resolve("bin/gradle").isFile || directory.resolve("bin/gradle.bat").isFile

  private fun downloadException(
    uri: URI,
    description: String,
    attempts: Int,
    cause: Throwable?,
  ): GradleException =
    GradleException("Could not download $description from $uri after $attempts attempt(s)", cause)

  private class DownloadFailure(private val status: Int) : IOException("HTTP $status") {
    val isTransient: Boolean
      get() = status == 408 || status == 429 || status in 500..599
  }

  companion object {
    private val logger = Logging.getLogger(GradleDistributionCache::class.java)
    private const val BUFFER_SIZE = 8192
    private const val CONNECT_TIMEOUT_MILLIS = 30_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MILLIS = 1_000L
    private const val KEY_LENGTH = 32
    private val CHECKSUM_PATTERN = Regex("([0-9a-fA-F]{64})")

    private fun sha256(value: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }

    private fun safeFileName(value: String): String =
      value.replace(Regex("[^A-Za-z0-9._-]"), "_")
  }
}

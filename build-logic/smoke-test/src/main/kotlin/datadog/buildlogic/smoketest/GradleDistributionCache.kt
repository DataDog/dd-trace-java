package datadog.buildlogic.smoketest

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Nested distributions, under `wrapper/` so CI restores them from its existing cache. */
internal const val NESTED_DISTRIBUTIONS_DIR = "wrapper/nested-dists"

/** Marks a complete install and records its source. */
private const val COMPLETION_MARKER = ".ok"

/** Gradle launcher jar pattern. */
private val LAUNCHER_JAR = Regex("gradle-launcher-.*\\.jar")

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 120_000
private const val LOCK_RETRY_DELAY_MS = 100L

/** Provisions [gradleVersion] once in [cacheDir], trying [distributionUris] in order. */
internal fun provisionGradleDistribution(
  cacheDir: File,
  gradleVersion: String,
  distributionUris: List<URI>,
  logger: Logger,
): File {
  require(distributionUris.isNotEmpty()) {
    "At least one Gradle distribution URI is required to provision Gradle $gradleVersion"
  }
  val installDir = File(cacheDir, "gradle-$gradleVersion-bin")
  val marker = File(installDir, COMPLETION_MARKER)

  installedDistributionRoot(installDir, gradleVersion, marker)?.let { return it }

  if (!cacheDir.isDirectory && !cacheDir.mkdirs() && !cacheDir.isDirectory) {
    throw GradleException("Could not create Gradle distribution cache: ${cacheDir.absolutePath}")
  }
  return withDistributionFileLock(File(cacheDir, "gradle-$gradleVersion-bin.lock")) {
    // Recheck after waiting for the file lock.
    installedDistributionRoot(installDir, gradleVersion, marker)
      ?.let { return@withDistributionFileLock it }
    install(installDir, marker, gradleVersion, distributionUris, logger)
    installedDistributionRoot(installDir, gradleVersion, marker)
      ?: throw GradleException(
        "Gradle $gradleVersion was unpacked into ${installDir.absolutePath} but no " +
          "distribution root could be found in it",
      )
  }
}

private fun <T> withDistributionFileLock(lockPath: File, action: () -> T): T {
  RandomAccessFile(lockPath, "rw").use { lockFile ->
    while (true) {
      val lock =
        try {
          lockFile.channel.lock()
        } catch (_: OverlappingFileLockException) {
          // Another caller in this JVM holds the lock.
          Thread.sleep(LOCK_RETRY_DELAY_MS)
          continue
        }
      lock.use {
        return action()
      }
    }
  }
}

private fun install(
  installDir: File,
  marker: File,
  gradleVersion: String,
  distributionUris: List<URI>,
  logger: Logger,
) {
  val failures = mutableListOf<String>()
  distributionUris.forEachIndexed { index, uri ->
    val archive = File(installDir.parentFile, "gradle-$gradleVersion-bin.zip.part")
    try {
      if (index > 0) {
        logger.lifecycle(
          "MASS_FALLBACK gradle-nested-distribution: {} unavailable, provisioning Gradle {} from {}",
          distributionUris[index - 1],
          gradleVersion,
          uri,
        )
      }
      installDir.deleteRecursively()
      if (!installDir.mkdirs()) {
        throw GradleException("Could not create ${installDir.absolutePath}")
      }
      logger.lifecycle("Provisioning Gradle {} for nested builds from {}", gradleVersion, uri)
      download(uri, archive)
      verifySha256(uri, archive, logger)
      unzip(archive, installDir)
      // Validate before marking the install complete.
      val root = installedDistributionRoot(installDir, gradleVersion, marker = null)
        ?: throw GradleException("does not contain a Gradle $gradleVersion distribution")
      markExecutable(root)
      marker.writeText(uri.toString())
      return
    } catch (e: Exception) {
      failures += "$uri -> ${e.message}"
      logger.warn(
        "Could not provision Gradle {} from {}: {}",
        gradleVersion,
        uri,
        e.message,
      )
    } finally {
      archive.delete()
    }
  }
  throw GradleException(
    "Could not provision the Gradle $gradleVersion distribution from any source:" +
      failures.joinToString(separator = "") { "\n  - $it" },
  )
}

/** Returns the distribution root, requiring [marker] when provided. */
private fun installedDistributionRoot(
  installDir: File,
  gradleVersion: String,
  marker: File?,
): File? {
  if (marker != null && !marker.isFile) {
    return null
  }
  val expected = File(installDir, "gradle-$gradleVersion")
  val root = if (expected.isDirectory) {
    expected
  } else {
    // Tolerate a nonstandard archive root name.
    installDir.listFiles()?.singleOrNull { it.isDirectory }
  }
  return root?.takeIf { isGradleDistribution(it) }
}

/** Rejects partial cache restores by checking the launcher script and jar. */
private fun isGradleDistribution(root: File): Boolean =
  File(root, "bin/${NestedGradleBuild.gradleExecutableName()}").isFile &&
    File(root, "lib").listFiles()?.any { LAUNCHER_JAR.matches(it.name) } == true

private fun download(uri: URI, target: File) {
  val connection = openConnection(uri)
  try {
    connection.getInputStream().use { input ->
      FileOutputStream(target).use { output ->
        input.copyTo(output, DEFAULT_BUFFER_SIZE)
      }
    }
  } finally {
    (connection as? HttpURLConnection)?.disconnect()
  }
}

/** Verifies a published SHA-256; missing checksums are allowed, mismatches trigger fallback. */
private fun verifySha256(uri: URI, archive: File, logger: Logger) {
  val expected = publishedSha256(uri, logger) ?: return
  val actual = sha256(archive)
  if (!expected.equals(actual, ignoreCase = true)) {
    throw GradleException(
      "SHA-256 mismatch for $uri (expected $expected, got $actual)",
    )
  }
}

private fun publishedSha256(uri: URI, logger: Logger): String? =
  try {
    val connection = openConnection(URI.create("$uri.sha256"))
    try {
      connection.getInputStream().use { it.readBytes() }
        .toString(Charsets.UTF_8)
        .trim()
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }
    } finally {
      (connection as? HttpURLConnection)?.disconnect()
    }
  } catch (e: Exception) {
    logger.info("No SHA-256 published next to {} ({}), skipping verification", uri, e.message)
    null
  }

private fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().buffered().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) {
        break
      }
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { String.format("%02x", it) }
}

private fun openConnection(uri: URI): URLConnection {
  val connection = uri.toURL().openConnection()
  connection.connectTimeout = CONNECT_TIMEOUT_MS
  connection.readTimeout = READ_TIMEOUT_MS
  connection.connect()
  if (connection is HttpURLConnection && connection.responseCode / 100 != 2) {
    val status = connection.responseCode
    connection.disconnect()
    throw GradleException("Unexpected HTTP status $status")
  }
  return connection
}

private fun unzip(archive: File, target: File) {
  val canonicalTargetPath = target.canonicalPath + File.separator
  ZipInputStream(archive.inputStream().buffered()).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      val destination = File(target, entry.name)
      if (!destination.canonicalPath.startsWith(canonicalTargetPath)) {
        throw GradleException("Zip entry escapes ${target.absolutePath}: ${entry.name}")
      }
      if (entry.isDirectory) {
        destination.mkdirs()
      } else {
        destination.parentFile.mkdirs()
        FileOutputStream(destination).use { output ->
          zip.copyTo(output, DEFAULT_BUFFER_SIZE)
        }
      }
      zip.closeEntry()
    }
  }
}

/** Restores executable bits not exposed by [ZipInputStream]. */
private fun markExecutable(distributionRoot: File) {
  File(distributionRoot, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
}

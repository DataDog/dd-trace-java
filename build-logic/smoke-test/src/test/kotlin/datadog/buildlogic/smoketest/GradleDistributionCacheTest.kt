package datadog.buildlogic.smoketest

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests the nested Gradle distribution cache with local archives. */
class GradleDistributionCacheTest {

  @TempDir
  lateinit var tempDir: Path

  private val logger = Logging.getLogger(GradleDistributionCacheTest::class.java)

  private val cacheDir get() = tempDir.resolve("cache").toFile()

  @Test
  fun `provisions from the first source and returns the distribution root`() {
    val distribution = writeDistributionArchive("primary.zip", VERSION)

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(root).isDirectory()
    assertThat(root.name).isEqualTo("gradle-$VERSION")
    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
    assertThat(File(root, "lib/gradle-launcher-$VERSION.jar")).exists()
  }

  @Test
  fun `falls back to the next source when the first is unreachable`() {
    val missing = tempDir.resolve("absent.zip").toFile().toURI()
    val fallback = writeDistributionArchive("fallback.zip", VERSION)

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(missing, fallback), logger)

    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
    assertThat(markerContent()).isEqualTo(fallback.toString())
  }

  @Test
  fun `falls back when the first source serves an archive that fails checksum verification`() {
    val corrupt = writeDistributionArchive("corrupt.zip", VERSION)
    File(corrupt).resolveSibling("corrupt.zip.sha256").writeText("0".repeat(64))
    val fallback = writeDistributionArchive("good.zip", VERSION)

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(corrupt, fallback), logger)

    assertThat(markerContent()).isEqualTo(fallback.toString())
    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
  }

  @Test
  fun `accepts a source whose published checksum matches`() {
    val distribution = writeDistributionArchive("verified.zip", VERSION)
    val archive = File(distribution)
    archive.resolveSibling("verified.zip.sha256").writeText(sha256Hex(archive))

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(markerContent()).isEqualTo(distribution.toString())
    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
  }

  @Test
  fun `reuses an already provisioned distribution instead of downloading again`() {
    val distribution = writeDistributionArchive("once.zip", VERSION)

    val first = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)
    // Remove the source to prove the second call uses the cache.
    assertThat(File(distribution).delete()).isTrue()
    val second = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(second).isEqualTo(first)
    assertThat(File(second, "bin/${launcherScriptName()}")).exists()
  }

  @Test
  fun `re-provisions when a previous attempt left the distribution incomplete`() {
    val distribution = writeDistributionArchive("resume.zip", VERSION)
    val installDir = File(cacheDir, "gradle-$VERSION-bin")
    // Simulate an interrupted unpack.
    File(installDir, "gradle-$VERSION/bin").mkdirs()

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
    assertThat(markerContent()).isEqualTo(distribution.toString())
  }

  @Test
  fun `re-provisions when the cached distribution lost its launcher script`() {
    val distribution = writeDistributionArchive("relaunch.zip", VERSION)
    provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)
    // Simulate a cache restore with a marker but missing content.
    assertThat(File(installedRoot(), "bin/${launcherScriptName()}").delete()).isTrue()

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(File(root, "bin/${launcherScriptName()}")).exists()
    assertThat(File(root, "lib/gradle-launcher-$VERSION.jar")).exists()
  }

  @Test
  fun `re-provisions when the cached distribution lost its launcher jar`() {
    val distribution = writeDistributionArchive("relib.zip", VERSION)
    provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)
    assertThat(File(installedRoot(), "lib/gradle-launcher-$VERSION.jar").delete()).isTrue()

    val root = provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger)

    assertThat(File(root, "lib/gradle-launcher-$VERSION.jar")).exists()
  }

  @Test
  fun `falls back when a source serves an archive that is not a Gradle distribution`() {
    val notADistribution = tempDir.resolve("bogus.zip").toFile()
    ZipOutputStream(notADistribution.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("gradle-$VERSION/bin/${launcherScriptName()}"))
      zip.write("#!/bin/sh\n".toByteArray())
      zip.closeEntry()
    }
    val fallback = writeDistributionArchive("real.zip", VERSION)

    val root = provisionGradleDistribution(
      cacheDir,
      VERSION,
      listOf(notADistribution.toURI(), fallback),
      logger,
    )

    assertThat(markerContent()).isEqualTo(fallback.toString())
    assertThat(File(root, "lib/gradle-launcher-$VERSION.jar")).exists()
  }

  @Test
  fun `reports every source that was tried when none can serve the distribution`() {
    val first = tempDir.resolve("first-absent.zip").toFile().toURI()
    val second = tempDir.resolve("second-absent.zip").toFile().toURI()

    assertThatThrownBy {
      provisionGradleDistribution(cacheDir, VERSION, listOf(first, second), logger)
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("first-absent.zip")
      .hasMessageContaining("second-absent.zip")
  }

  @Test
  fun `concurrent callers provision once and all receive the same distribution`() {
    // A counting source proves concurrent callers download only once.
    val downloads = AtomicInteger()
    val server = countingDistributionServer(downloads)
    try {
      val threads = 8
      val ready = CyclicBarrier(threads)
      val roots = ConcurrentLinkedQueue<File>()
      val failures = ConcurrentLinkedQueue<Throwable>()
      val workers = (1..threads).map {
        Thread {
          try {
            ready.await(30, TimeUnit.SECONDS)
            roots += provisionGradleDistribution(cacheDir, VERSION, listOf(server.uri), logger)
          } catch (e: Throwable) {
            failures += e
          }
        }.apply { start() }
      }
      workers.forEach { it.join(TimeUnit.MINUTES.toMillis(1)) }

      assertThat(failures).isEmpty()
      assertThat(roots).hasSize(threads)
      assertThat(roots.distinct()).hasSize(1)
      assertThat(downloads).hasValue(1)
      assertThat(File(roots.first(), "lib/gradle-launcher-$VERSION.jar")).exists()
    } finally {
      server.close()
    }
  }

  @Test
  fun `waits when the distribution lock is already held in this JVM`() {
    val distribution = writeDistributionArchive("overlapping-lock.zip", VERSION)
    assertThat(cacheDir.mkdirs()).isTrue()
    val started = CountDownLatch(1)
    val result = CompletableFuture<File>()
    val worker = Thread {
      started.countDown()
      try {
        result.complete(
          provisionGradleDistribution(cacheDir, VERSION, listOf(distribution), logger),
        )
      } catch (e: Throwable) {
        result.completeExceptionally(e)
      }
    }

    val root =
      try {
        RandomAccessFile(File(cacheDir, "gradle-$VERSION-bin.lock"), "rw").use { lockFile ->
          lockFile.channel.lock().use {
            worker.start()
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(200)
            assertThat(result.isDone).isFalse()
          }
        }
        result.get(30, TimeUnit.SECONDS)
      } finally {
        worker.join(TimeUnit.SECONDS.toMillis(30))
      }

    assertThat(root).isDirectory()
    assertThat(File(root, "lib/gradle-launcher-$VERSION.jar")).exists()
  }

  @Test
  fun `requires at least one source`() {
    assertThatThrownBy {
      provisionGradleDistribution(cacheDir, VERSION, emptyList(), logger)
    }.isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `rejects archives containing entries outside the target directory`() {
    val escaping = tempDir.resolve("escaping.zip").toFile()
    ZipOutputStream(escaping.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("../escaped.txt"))
      zip.write("nope".toByteArray())
      zip.closeEntry()
    }

    assertThatThrownBy {
      provisionGradleDistribution(cacheDir, VERSION, listOf(escaping.toURI()), logger)
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("escaped.txt")
    assertThat(tempDir.resolve("escaped.txt").toFile()).doesNotExist()
  }

  @Test
  fun `distribution sources put MASS first and keep upstream as a fallback`() {
    assertThat(gradleDistributionUris("https://mass.example", VERSION).map { it.toString() })
      .containsExactly(
        "https://mass.example/internal/artifact/services.gradle.org/distributions/gradle-$VERSION-bin.zip",
        "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip",
      )
  }

  @Test
  fun `distribution sources fall back to upstream only when MASS is not configured`() {
    val upstreamOnly = listOf("https://services.gradle.org/distributions/gradle-$VERSION-bin.zip")

    assertThat(gradleDistributionUris(null, VERSION).map { it.toString() })
      .isEqualTo(upstreamOnly)
    assertThat(gradleDistributionUris("", VERSION).map { it.toString() })
      .isEqualTo(upstreamOnly)
    assertThat(gradleDistributionUris("   ", VERSION).map { it.toString() })
      .isEqualTo(upstreamOnly)
  }

  private fun markerContent(): String =
    File(cacheDir, "gradle-$VERSION-bin/.ok").readText()

  /** Writes the minimal archive shape Gradle accepts. */
  private fun writeDistributionArchive(name: String, version: String): URI {
    val archive = tempDir.resolve(name).toFile()
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("gradle-$version/"))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("gradle-$version/bin/${launcherScriptName()}"))
      zip.write("#!/bin/sh\necho gradle $version\n".toByteArray())
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("gradle-$version/lib/gradle-launcher-$version.jar"))
      zip.write("launcher-$name".toByteArray())
      zip.closeEntry()
    }
    return archive.toURI()
  }

  private fun launcherScriptName(): String = NestedGradleBuild.gradleExecutableName()

  /** Serves an archive over HTTP and counts archive requests. */
  private fun countingDistributionServer(downloads: AtomicInteger): CountingServer {
    val archive = File(writeDistributionArchive("counted.zip", VERSION))
    val bytes = archive.readBytes()
    val checksum = sha256Hex(archive).toByteArray()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/gradle-$VERSION-bin.zip") { exchange ->
      downloads.incrementAndGet()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/gradle-$VERSION-bin.zip.sha256") { exchange ->
      exchange.sendResponseHeaders(200, checksum.size.toLong())
      exchange.responseBody.use { it.write(checksum) }
    }
    server.start()
    return CountingServer(server, VERSION)
  }

  private class CountingServer(private val server: HttpServer, version: String) {
    val uri: URI =
      URI.create("http://127.0.0.1:${server.address.port}/gradle-$version-bin.zip")

    fun close() = server.stop(0)
  }

  private fun installedRoot(): File =
    File(cacheDir, "gradle-$VERSION-bin/gradle-$VERSION")

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return digest.joinToString("") { String.format("%02x", it) }
  }

  private companion object {
    const val VERSION = "8.14.5"
  }
}

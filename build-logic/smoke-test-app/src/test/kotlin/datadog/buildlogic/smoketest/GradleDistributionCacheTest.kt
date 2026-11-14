package datadog.buildlogic.smoketest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GradleDistributionCacheTest {

  @TempDir
  lateinit var tempDir: Path

  private lateinit var server: HttpServer

  @BeforeEach
  fun setUp() {
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `reuses a verified installation across fresh cache instances`() {
    val archive = gradleArchive("8.14.6")
    val checksumRequests = AtomicInteger()
    val archiveRequests = AtomicInteger()
    serve("/gradle-8.14.6-bin.zip", archive) { archiveRequests.incrementAndGet(); 200 }
    serve("/gradle-8.14.6-bin.zip.sha256", sha256(archive)) { checksumRequests.incrementAndGet(); 200 }
    val uri = distributionUri()

    val first = cache().getInstallation(uri, "8.14.6")
    val second = cache().getInstallation(uri, "8.14.6")

    assertThat(second).isEqualTo(first)
    assertThat(first.resolve("bin/gradle")).isFile
    assertThat(archiveRequests).hasValue(1)
    assertThat(checksumRequests).hasValue(1)
  }

  @Test
  fun `serializes concurrent initialization of one installation`() {
    val archive = gradleArchive("8.14.6")
    val archiveRequests = AtomicInteger()
    serve("/gradle-8.14.6-bin.zip", archive) {
      archiveRequests.incrementAndGet()
      Thread.sleep(100)
      200
    }
    serve("/gradle-8.14.6-bin.zip.sha256", sha256(archive))
    val uri = distributionUri()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val results = executor.invokeAll(
        listOf(
          Callable { cache().getInstallation(uri, "8.14.6") },
          Callable { cache().getInstallation(uri, "8.14.6") },
        ),
      )

      assertThat(results.map { it.get() }.distinct()).hasSize(1)
      assertThat(archiveRequests).hasValue(1)
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }
  }

  @Test
  fun `retries transient archive download failures`() {
    val archive = gradleArchive("8.14.6")
    val archiveRequests = AtomicInteger()
    serve("/gradle-8.14.6-bin.zip", archive) {
      if (archiveRequests.incrementAndGet() < 3) 503 else 200
    }
    serve("/gradle-8.14.6-bin.zip.sha256", sha256(archive))

    val installation = cache().getInstallation(distributionUri(), "8.14.6")

    assertThat(installation.resolve("bin/gradle")).isFile
    assertThat(archiveRequests).hasValue(3)
  }

  @Test
  fun `preserves interruption during retry backoff`() {
    serve("/gradle-8.14.6-bin.zip.sha256", "temporarily unavailable") { 503 }
    val cache = GradleDistributionCache(
      tempDir.resolve("cache").toFile(),
      sleep = { throw InterruptedException("interrupted backoff") },
    )

    try {
      assertThatThrownBy { cache.getInstallation(distributionUri(), "8.14.6") }
        .isInstanceOf(GradleException::class.java)
        .hasCauseInstanceOf(InterruptedException::class.java)
        .hasMessageContaining("after 1 attempt(s)")
      assertThat(Thread.currentThread().isInterrupted).isTrue()
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `propagates permanent and exhausted download failures`() {
    val permanentRequests = AtomicInteger()
    serve("/gradle-8.14.6-bin-2.zip.sha256", "not available") { permanentRequests.incrementAndGet(); 404 }
    assertThatThrownBy {
      cache().getInstallation(distributionUri("gradle-8.14.6-bin-2.zip"), "8.14.6")
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("after 1 attempt(s)")
    assertThat(permanentRequests).hasValue(1)

    val exhaustedRequests = AtomicInteger()
    serve("/gradle-8.14.6-bin-3.zip.sha256", "temporarily unavailable") {
      exhaustedRequests.incrementAndGet()
      503
    }
    assertThatThrownBy {
      cache().getInstallation(distributionUri("gradle-8.14.6-bin-3.zip"), "8.14.6")
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("after 3 attempt(s)")
    assertThat(exhaustedRequests).hasValue(3)
  }

  private fun cache(): GradleDistributionCache =
    GradleDistributionCache(
      tempDir.resolve("cache").toFile(),
      sleep = { _: Long -> },
    )

  private fun distributionUri(name: String = "gradle-8.14.6-bin.zip"): URI =
    URI.create("http://localhost:${server.address.port}/$name")

  private fun serve(
    path: String,
    body: String,
    onRequest: () -> Int = { 200 },
  ) = serve(path, body.toByteArray(), onRequest)

  private fun serve(
    path: String,
    body: ByteArray,
    status: () -> Int,
  ) {
    server.createContext(path) { exchange: HttpExchange ->
      val responseStatus = status()
      exchange.sendResponseHeaders(responseStatus, if (responseStatus == 200) body.size.toLong() else -1)
      if (responseStatus == 200) {
        exchange.responseBody.use { it.write(body) }
      } else {
        exchange.close()
      }
    }
  }

  private fun gradleArchive(version: String): ByteArray =
    ByteArrayOutputStream().also { output ->
      ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("gradle-$version/bin/gradle"))
        zip.write("#!/bin/sh\n".toByteArray())
        zip.closeEntry()
      }
    }.toByteArray()

  private fun sha256(content: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

}

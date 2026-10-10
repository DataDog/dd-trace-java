package datadog.gradle.plugin.muzzle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import datadog.gradle.plugin.muzzle.MuzzleDependencyAge.Timestamp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.aether.repository.RemoteRepository
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class MuzzleDependencyAgeTest {
  private val now = Instant.parse("2026-09-30T12:00:00Z")
  private val repositories = listOf(repository("central", "https://repo.example/maven2"))
  private val proxy = repository("central-proxy", "https://proxy.example/maven2/")
  private val centralPom = "https://repo1.maven.org/maven2/com/example/lib/1.0/lib-1.0.pom"
  private val requested = mutableListOf<String>()
  private val warnings = mutableListOf<String>()

  @Test
  fun `accepts the exact cutoff and older versions but defers newer versions`() {
    val policy = policy { url ->
      Timestamp(
        when {
          url.contains("/1.0/") -> now.minusSeconds(48 * 3600L + 1)
          url.contains("/1.1/") -> now.minusSeconds(48 * 3600L)
          else -> now.minusSeconds(48 * 3600L - 1)
        }
      )
    }

    assertThat(eligible(policy, "1.0")).isTrue()
    assertThat(eligible(policy, "1.1")).isTrue()
    assertThat(eligible(policy, "1.2")).isFalse()
    assertThat(warnings).singleElement().asString()
      .contains("com.example:lib:1.2", "eligible at 2026-09-30T12:00:01Z", "48h cooldown")
  }

  @ParameterizedTest
  @CsvSource(value = ["central,true", "central-proxy,false"])
  fun `uses configured extra repositories before any Central fallback`(primaryId: String, oldEnough: Boolean) {
    val primary = if (primaryId == "central-proxy") proxy else repositories.single()
    val extra = repository("extra", "https://extra.example/maven2/")
    val policy = policy { url ->
      if (url.startsWith(primary.url)) {
        Timestamp(null, "HTTP 404")
      } else {
        Timestamp(if (oldEnough) now.minusSeconds(72 * 3600L) else now)
      }
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(primary, extra))).isEqualTo(oldEnough)
    assertThat(requested).containsExactly(
      "${primary.url.trimEnd('/')}/com/example/lib/1.0/lib-1.0.pom",
      "${extra.url}com/example/lib/1.0/lib-1.0.pom"
    )
    if (oldEnough) assertThat(warnings).isEmpty()
    else assertThat(warnings).singleElement().asString().contains("Muzzle deferring com.example:lib:1.0")
  }

  @ParameterizedTest
  @ValueSource(longs = [172799L, 172800L, 172801L])
  fun `Central timestamp fallback enforces the cooldown when proxy headers are missing`(ageSeconds: Long) {
    val policy = policy { url ->
      if (url == centralPom) {
        Timestamp(now.minusSeconds(ageSeconds))
      } else {
        Timestamp(null, "missing or invalid Last-Modified header")
      }
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy)))
      .isEqualTo(ageSeconds >= 48 * 3600L)
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      centralPom
    )
    if (ageSeconds < 48 * 3600L) {
      assertThat(warnings).singleElement().asString().contains("Muzzle deferring com.example:lib:1.0", "48h cooldown")
    } else {
      assertThat(warnings).isEmpty()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `does not contact Central when the proxy provides a usable timestamp`(oldEnough: Boolean) {
    val policy = policy { url ->
      assertThat(url).startsWith(proxy.url)
      Timestamp(if (oldEnough) now.minusSeconds(72 * 3600L) else now)
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isEqualTo(oldEnough)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `caches proxy and Central timestamps including unavailable timestamps`(available: Boolean) {
    val policy = policy { url ->
      if (url == centralPom) {
        if (available) Timestamp(now.minusSeconds(72 * 3600L)) else Timestamp(null, "HTTP 429")
      } else Timestamp(null, "missing or invalid Last-Modified header")
    }
    repeat(2) { assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isTrue() }
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom", centralPom
    )
    if (available) assertThat(warnings).isEmpty()
    else assertThat(warnings).hasSize(2).allSatisfy {
      assertThat(it).contains("Muzzle retaining com.example:lib:1.0", "cannot verify publication age", "central-timestamp: HTTP 429")
    }
  }

  @Test
  fun `does not repeat unreachable Central timestamp requests for different versions`() {
    val policy = policy { url ->
      if (url.startsWith(proxy.url)) {
        Timestamp(null, "missing or invalid Last-Modified header")
      } else {
        throw SocketTimeoutException("https://secret-token.example/")
      }
    }

    repeat(100) { version ->
      assertThat(policy.isEligible("com.example", "lib", "1.$version", listOf(proxy))).isTrue()
    }

    assertThat(requested.count { it.startsWith(proxy.url) }).isEqualTo(100)
    assertThat(requested.count { it.startsWith("https://repo1.maven.org/") }).isEqualTo(1)
    assertThat(warnings).hasSize(100).allSatisfy {
      assertThat(it).contains(
        "cannot verify publication age",
        "central-timestamp: SocketTimeoutException",
        "timestamp lookups disabled for this repository for this build",
        "continuing without age verification"
      ).doesNotContain("secret-token")
    }
  }

  @Test
  fun `continues using proxy timestamps after the Central timestamp fallback times out`() {
    val policy = policy { url ->
      when {
        !url.startsWith(proxy.url) -> throw SocketTimeoutException()
        url.contains("/1.0/") -> Timestamp(null, "missing or invalid Last-Modified header")
        else -> Timestamp(now)
      }
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isTrue()
    assertThat(policy.isEligible("com.example", "lib", "1.1", listOf(proxy))).isFalse()
    assertThat(requested).hasSize(3)
    assertThat(warnings[1]).contains("Muzzle deferring com.example:lib:1.1")
  }

  @Test
  fun `continues trying healthy repositories after a connection failure`() {
    val policy = policy { url ->
      if (url.startsWith("https://repo.example/")) throw ConnectException()
      Timestamp(now)
    }
    val repos = repositories + repository("extra", "https://extra.example/maven2/")

    repeat(2) { version ->
      assertThat(policy.isEligible("com.example", "lib", "1.$version", repos)).isFalse()
    }

    assertThat(requested.count { it.startsWith("https://repo.example/") }).isEqualTo(1)
    assertThat(requested.count { it.startsWith("https://extra.example/") }).isEqualTo(2)
    assertThat(warnings).hasSize(2).allSatisfy { assertThat(it).contains("Muzzle deferring") }
  }

  @Test
  fun `a subsequent build retries repositories after transport failures`() {
    val lookup: (String) -> Timestamp = {
      if (requested.size == 1) throw SocketTimeoutException()
      Timestamp(now)
    }
    val first = policy(lookup)
    val second = policy(lookup)

    assertThat(eligible(first, "1.0")).isTrue()
    assertThat(eligible(first, "1.1")).isTrue()
    assertThat(eligible(second, "1.0")).isFalse()
    assertThat(requested).hasSize(2)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `retains cached publication timestamps when another version has a transport failure`(oldEnough: Boolean) {
    val policy = policy {
      if (requested.size == 1) {
        Timestamp(if (oldEnough) now.minusSeconds(72 * 3600L) else now)
      } else {
        throw SocketTimeoutException()
      }
    }

    assertThat(eligible(policy, "1.0")).isEqualTo(oldEnough)
    assertThat(eligible(policy, "1.1")).isTrue()
    assertThat(eligible(policy, "1.0")).isEqualTo(oldEnough)
    assertThat(requested).hasSize(2)
  }

  @ParameterizedTest
  @ValueSource(strings = ["HTTP 404", "missing or invalid Last-Modified header"])
  fun `artifact-specific missing timestamps do not disable a repository`(reason: String) {
    val policy = policy {
      if (requested.size == 1) Timestamp(null, reason) else Timestamp(now)
    }

    assertThat(eligible(policy, "1.0")).isTrue()
    assertThat(eligible(policy, "1.1")).isFalse()
    assertThat(requested).hasSize(2)
  }

  @Test
  fun `a missing local file does not disable timestamps for other local versions`() {
    val policy = policy {
      if (requested.size == 1) throw FileNotFoundException()
      Timestamp(now)
    }
    val repos = listOf(repository("fixture", "file:/tmp/muzzle-repo/"))

    assertThat(policy.isEligible("com.example", "lib", "1.0", repos)).isTrue()
    assertThat(policy.isEligible("com.example", "lib", "1.1", repos)).isFalse()
    assertThat(requested).hasSize(2)
  }

  @Test
  fun `does not duplicate Central timestamp requests when Central is already configured`() {
    val policy = policy { url ->
      Timestamp(null, "HTTP 404")
    }
    val repos = listOf(proxy, repository("central", "https://repo1.maven.org/maven2"))

    assertThat(policy.isEligible("com.example", "lib", "1.0", repos)).isTrue()
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      centralPom
    )
    assertThat(warnings).singleElement().asString().doesNotContain("central-timestamp")
  }

  @ParameterizedTest
  @CsvSource(value = [
    "private,https://custom.example/maven2/",
    "custom-proxy,https://custom.example/maven2/",
    "central,https://custom.example/maven2/",
    "central-proxy,file:/tmp/muzzle-repo/"
  ])
  fun `custom and local repositories do not add a Central timestamp fallback`(id: String, url: String) {
    val repo = repository(id, url)
    val policy = policy { requestedUrl ->
      assertThat(requestedUrl).startsWith(repo.url)
      Timestamp(null, "missing or invalid Last-Modified header")
    }
    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(repo))).isTrue()
    assertThat(warnings).singleElement().asString().doesNotContain("central-timestamp")
  }

  @Test
  fun `does not fall back to an older timestamp for a release known to be too new`() {
    val policy = policy { url ->
      Timestamp(now)
    }

    assertThat(
      policy.isEligible(
        "com.example",
        "lib",
        "1.0",
        repositories + repository("extra", "https://extra.example")
      )
    ).isFalse()
    assertThat(requested).hasSize(1)
  }

  @Test
  fun `retains unverified versions with a warning without logging repository credentials`() {
    val policy = policy { Timestamp(null, "HTTP 403") }
    val repos = listOf(repository("private", "https://repo.example/secret-token/secure"))

    assertThat(policy.isEligible("com.example", "lib", "1.0", repos)).isTrue()
    assertThat(warnings).singleElement().asString()
      .contains("Muzzle retaining com.example:lib:1.0", "cannot verify publication age", "private: HTTP 403")
      .doesNotContain("secret-token")
  }

  @Test
  fun `caches timestamps by repository and coordinate including unknown timestamps`() {
    val policy = policy { url ->
      Timestamp(null, "HTTP 404")
    }
    repeat(2) { eligible(policy, "1.0") }
    eligible(policy, "1.1")
    policy.isEligible("com.example", "lib", "1.0", listOf(repository("extra", "https://extra.example")))

    assertThat(requested).hasSize(3)
  }

  @Test
  fun `a subsequent build reconsiders a deferred release using its own cutoff`() {
    val publishedAt = now.minusSeconds(47 * 3600L)
    val first = MuzzleDependencyAge(48, now, { Timestamp(publishedAt) }, warnings::add)
    val second = MuzzleDependencyAge(48, now.plusSeconds(3600), { Timestamp(publishedAt) }, warnings::add)

    assertThat(eligible(first)).isFalse()
    assertThat(eligible(second)).isTrue()
  }

  @Test
  fun `zero bypasses lookup even when no repositories are available`() {
    val policy = MuzzleDependencyAge(0, now, { error("Unexpected timestamp lookup") }, warnings::add)

    assertThat(policy.isEligible("com.example", "lib", "1.0", emptyList())).isTrue()
    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isTrue()
    assertThat(warnings).isEmpty()
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 48, 72])
  fun `parses valid cooldown configuration`(hours: Int) {
    assertThat(parseMinimumDependencyAgeHours(hours.toString())).isEqualTo(hours)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "-1", "abc", "1.5", "2147483648"])
  fun `rejects invalid configuration`(raw: String) {
    assertThatThrownBy { parseMinimumDependencyAgeHours(raw) }
      .isInstanceOf(GradleException::class.java).hasMessageContaining("non-negative integer")
  }

  @Test
  @Timeout(10)
  fun `a stalled HTTP response times out without retries and retains later versions`() {
    val requests = AtomicInteger()
    val releaseResponse = CountDownLatch(1)
    withRepository({ _ ->
      requests.incrementAndGet()
      releaseResponse.await(10, SECONDS)
    }) { repo ->
      try {
        val policy = MuzzleDependencyAge(48, now, warn = warnings::add)
        repeat(3) { version ->
          assertThat(policy.isEligible("com.example", "lib", "1.$version", listOf(repo))).isTrue()
        }
      } finally {
        releaseResponse.countDown()
      }
    }
    assertThat(requests).hasValue(1)
    assertThat(warnings).hasSize(3).allSatisfy {
      assertThat(it).contains("SocketTimeoutException", "continuing without age verification")
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = [
    "200|Mon, 28 Sep 2026 12:00:00 GMT|",
    "200||missing or invalid Last-Modified",
    "200|not-a-date|missing or invalid Last-Modified",
    "403||HTTP 403", "404||HTTP 404", "429||HTTP 429", "500||HTTP 500", "503||HTTP 503"
  ])
  fun `HEAD requests read publication times and cache unavailable timestamps`(
    status: Int, header: String?, reason: String?
  ) {
    val requests = AtomicInteger()
    withRepository({ exchange ->
      assertThat(exchange.requestMethod).isEqualTo("HEAD")
      assertThat(exchange.requestURI.path).isEqualTo("/com/example/lib/1.0/lib-1.0.pom")
      requests.incrementAndGet()
      header?.let { exchange.responseHeaders.add("Last-Modified", it) }
      exchange.sendResponseHeaders(status, -1)
    }) { repo ->
      val policy = MuzzleDependencyAge(48, now, warn = warnings::add)
      repeat(2) { assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(repo))).isTrue() }
    }
    assertThat(requests).hasValue(1)
    if (reason == null) assertThat(warnings).isEmpty()
    else assertThat(warnings).hasSize(2).allSatisfy { assertThat(it).contains(reason) }
  }

  private fun policy(lookup: (String) -> Timestamp) = MuzzleDependencyAge(48, now, { url ->
    requested.add(url)
    lookup(url)
  }, warnings::add)

  private fun eligible(policy: MuzzleDependencyAge, version: String = "1.0") = policy.isEligible("com.example", "lib", version, repositories)

  private fun repository(id: String, url: String) = RemoteRepository.Builder(id, "default", url).build()

  private fun withRepository(respond: (HttpExchange) -> Unit, check: (RemoteRepository) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      try {
        respond(exchange)
      } finally {
        exchange.close()
      }
    }
    server.start()
    try {
      check(repository("fixture", "http://127.0.0.1:${server.address.port}"))
    } finally {
      server.stop(0)
    }
  }
}

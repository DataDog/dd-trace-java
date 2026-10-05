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

  @Test
  fun `falls back to another repository when age is unavailable`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
      if (url.startsWith("https://repo.example/")) {
        Timestamp(null, "HTTP 404")
      } else {
        Timestamp(now.minusSeconds(72 * 3600L))
      }
    }
    val repos = repositories + repository("extra", "https://extra.example/maven2/")

    assertThat(policy.isEligible("com.example", "lib", "1.0", repos)).isTrue()
    assertThat(requested).containsExactly(
      "https://repo.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      "https://extra.example/maven2/com/example/lib/1.0/lib-1.0.pom"
    )
    assertThat(warnings).isEmpty()
  }

  @ParameterizedTest
  @ValueSource(longs = [172799L, 172800L, 172801L])
  fun `Central timestamp fallback enforces the cooldown when proxy headers are missing`(ageSeconds: Long) {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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

  @Test
  fun `tries configured extra repositories before the Central timestamp fallback`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
      if (url.startsWith(proxy.url)) Timestamp(null, "HTTP 404") else Timestamp(now)
    }
    val extra = repository("extra", "https://extra.example/maven2/")

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy, extra))).isFalse()
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      "https://extra.example/maven2/com/example/lib/1.0/lib-1.0.pom"
    )
  }

  @Test
  fun `caches proxy and Central timestamps across repeated checks`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
      if (url == centralPom) Timestamp(now.minusSeconds(72 * 3600L)) else Timestamp(null, "HTTP 404")
    }

    repeat(2) {
      assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isTrue()
    }
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      centralPom
    )
    assertThat(policy.timestampLookupCount).isEqualTo(2)
    assertThat(warnings).isEmpty()
  }

  @Test
  fun `retains the dependency when proxy and Central timestamps are unknown and caches failures`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
      Timestamp(null, if (url == centralPom) "HTTP 429" else "missing or invalid Last-Modified header")
    }

    repeat(2) {
      assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(proxy))).isTrue()
    }
    assertThat(requested).containsExactly(
      "https://proxy.example/maven2/com/example/lib/1.0/lib-1.0.pom",
      centralPom
    )
    assertThat(warnings).hasSize(2).allSatisfy {
      assertThat(it).contains("Muzzle retaining com.example:lib:1.0", "cannot verify publication age", "central-timestamp: HTTP 429")
    }
  }

  @Test
  fun `does not repeat unreachable Central timestamp requests for different versions`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
    val requests = AtomicInteger()
    val lookup: (String) -> Timestamp = {
      if (requests.incrementAndGet() == 1) throw SocketTimeoutException()
      Timestamp(now)
    }
    val first = policy(lookup)
    val second = policy(lookup)

    assertThat(eligible(first, "1.0")).isTrue()
    assertThat(eligible(first, "1.1")).isTrue()
    assertThat(eligible(second, "1.0")).isFalse()
    assertThat(requests).hasValue(2)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `retains cached publication timestamps when another version has a transport failure`(oldEnough: Boolean) {
    val requests = AtomicInteger()
    val policy = policy {
      if (requests.incrementAndGet() == 1) {
        Timestamp(if (oldEnough) now.minusSeconds(72 * 3600L) else now)
      } else {
        throw SocketTimeoutException()
      }
    }

    assertThat(eligible(policy, "1.0")).isEqualTo(oldEnough)
    assertThat(eligible(policy, "1.1")).isTrue()
    assertThat(eligible(policy, "1.0")).isEqualTo(oldEnough)
    assertThat(requests).hasValue(2)
  }

  @ParameterizedTest
  @ValueSource(strings = ["HTTP 404", "missing or invalid Last-Modified header"])
  fun `artifact-specific missing timestamps do not disable a repository`(reason: String) {
    val requests = AtomicInteger()
    val policy = policy {
      if (requests.incrementAndGet() == 1) Timestamp(null, reason) else Timestamp(now)
    }

    assertThat(eligible(policy, "1.0")).isTrue()
    assertThat(eligible(policy, "1.1")).isFalse()
    assertThat(requests).hasValue(2)
  }

  @Test
  fun `a missing local file does not disable timestamps for other local versions`() {
    val requests = AtomicInteger()
    val policy = policy {
      if (requests.incrementAndGet() == 1) throw FileNotFoundException()
      Timestamp(now)
    }
    val repos = listOf(repository("fixture", "file:/tmp/muzzle-repo/"))

    assertThat(policy.isEligible("com.example", "lib", "1.0", repos)).isTrue()
    assertThat(policy.isEligible("com.example", "lib", "1.1", repos)).isFalse()
    assertThat(requests).hasValue(2)
  }

  @Test
  fun `does not duplicate Central timestamp requests when Central is already configured`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
  @ValueSource(strings = ["private", "custom-proxy", "central"])
  fun `does not add a Central timestamp fallback to custom repositories`(id: String) {
    val repo = repository(id, "https://custom.example/maven2/")
    val policy = policy { url ->
      assertThat(url).startsWith(repo.url)
      Timestamp(null, "HTTP 404")
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(repo))).isTrue()
    assertThat(warnings).singleElement().asString().doesNotContain("central-timestamp")
  }

  @Test
  fun `does not add a Central timestamp fallback to local repository fixtures`() {
    val repo = repository("central-proxy", "file:/tmp/muzzle-repo/")
    val policy = policy { url ->
      assertThat(url).startsWith(repo.url)
      Timestamp(null, "missing or invalid Last-Modified header")
    }

    assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(repo))).isTrue()
    assertThat(warnings).singleElement().asString().doesNotContain("central-timestamp")
  }

  @Test
  fun `does not fall back to an older timestamp for a release known to be too new`() {
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
    val requested = mutableListOf<String>()
    val policy = policy { url ->
      requested.add(url)
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
    assertThat(policy.timestampLookupCount).isZero()
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
  fun `reads POM publication time with HEAD and caches it`() {
    val requests = AtomicInteger()
    withRepository({ exchange ->
      assertThat(exchange.requestMethod).isEqualTo("HEAD")
      assertThat(exchange.requestURI.path).isEqualTo("/com/example/lib/1.0/lib-1.0.pom")
      requests.incrementAndGet()
      exchange.responseHeaders.add("Last-Modified", "Mon, 28 Sep 2026 12:00:00 GMT")
      exchange.sendResponseHeaders(200, -1)
    }) { repo ->
      val policy = MuzzleDependencyAge(48, now, warn = warnings::add)
      repeat(2) {
        assertThat(policy.isEligible("com.example", "lib", "1.0", listOf(repo))).isTrue()
      }
    }
    assertThat(requests).hasValue(1)
    assertThat(warnings).isEmpty()
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
  @ValueSource(strings = ["", "not-a-date"])
  fun `retains versions with missing and malformed Last-Modified headers`(header: String) {
    withRepository({ exchange ->
      if (header.isNotEmpty()) exchange.responseHeaders.add("Last-Modified", header)
      exchange.sendResponseHeaders(200, -1)
    }) { repo ->
      assertThat(
        MuzzleDependencyAge(48, now, warn = warnings::add)
          .isEligible("com.example", "lib", "1.0", listOf(repo))
      ).isTrue()
    }
    assertThat(warnings).singleElement().asString().contains("missing or invalid Last-Modified")
  }

  @ParameterizedTest
  @ValueSource(ints = [403, 404, 429, 500, 503])
  fun `does not retry HTTP failures and retains versions when repositories are unavailable`(status: Int) {
    val requests = AtomicInteger()
    withRepository({ exchange ->
      requests.incrementAndGet()
      exchange.sendResponseHeaders(status, -1)
    }) { repo ->
      assertThat(
        MuzzleDependencyAge(48, now, warn = warnings::add)
          .isEligible("com.example", "lib", "1.0", listOf(repo))
      ).isTrue()
    }
    assertThat(requests).hasValue(1)
    assertThat(warnings).singleElement().asString().contains("HTTP $status")
  }

  private fun policy(lookup: (String) -> Timestamp) = MuzzleDependencyAge(48, now, lookup, warnings::add)

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

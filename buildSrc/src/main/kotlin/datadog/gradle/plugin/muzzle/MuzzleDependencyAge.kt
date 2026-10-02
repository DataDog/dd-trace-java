package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.time.Instant
import java.time.temporal.ChronoUnit.HOURS
import java.util.concurrent.ConcurrentHashMap

/** Publication timestamps and a fixed cutoff shared by muzzle checks in one build. */
internal class MuzzleDependencyAge(
  val minimumAgeHours: Int,
  buildStartedAt: Instant,
  private val lookup: (String) -> Timestamp = ::readTimestamp,
  private val warn: (String) -> Unit = { log.warn(it) }
) {
  data class Timestamp(val publishedAt: Instant?, val reason: String = "")

  private val cutoff = buildStartedAt.minus(minimumAgeHours.toLong(), HOURS)
  private val timestamps = ConcurrentHashMap<String, Timestamp>()
  private val unavailableRepositories = ConcurrentHashMap<String, Timestamp>()

  init {
    require(minimumAgeHours >= 0) { "Muzzle minimum dependency age must be non-negative" }
  }

  fun isEligible(group: String, module: String, version: String, repositories: List<RemoteRepository>): Boolean {
    if (minimumAgeHours == 0) return true

    val coordinate = "$group:$module:$version"
    val pomPath = "${group.replace('.', '/')}/$module/$version/$module-$version.pom"
    val failures = mutableListOf<String>()
    // Central proxies may omit Last-Modified. This fallback is only for timestamps,
    // not version discovery or artifact downloads; local and custom repositories stay unchanged.
    val timestampRepositories = if (repositories.any {
        it.id == "central-proxy" && (it.url.startsWith("https://") || it.url.startsWith("http://"))
      }
    ) {
      (repositories + centralTimestampRepository).distinctBy { it.url.trimEnd('/') }
    } else {
      repositories
    }
    for (repository in timestampRepositories) {
      val repositoryUrl = repository.url.trimEnd('/')
      val url = "$repositoryUrl/$pomPath"
      val timestamp = timestamps[url] ?: unavailableRepositories[repositoryUrl] ?: timestamps.computeIfAbsent(url) {
        try {
          lookup(it)
        } catch (e: IOException) {
          if (repositoryUrl.startsWith("https://") || repositoryUrl.startsWith("http://")) {
            // Avoid repeating optional network timeouts for every sampled version in this build.
            Timestamp(null, "${e.javaClass.simpleName}; timestamp lookups disabled for this repository for this build")
              .also { unavailableRepositories.putIfAbsent(repositoryUrl, it) }
          } else {
            Timestamp(null, e.javaClass.simpleName)
          }
        }
      }
      val publishedAt = timestamp.publishedAt
      if (publishedAt != null) {
        if (publishedAt <= cutoff) return true
        warn(
          "Muzzle deferring $coordinate: published $publishedAt, eligible at " +
            "${publishedAt.plus(minimumAgeHours.toLong(), HOURS)} (${minimumAgeHours}h cooldown)"
        )
        return false
      }
      // Repository URLs and exception messages can contain credentials.
      failures.add("${repository.id}: ${timestamp.reason}")
    }
    warn("Muzzle retaining $coordinate: cannot verify publication age (${failures.joinToString("; ")}); continuing without age verification")
    return true
  }

  companion object {
    private val log = Logging.getLogger(MuzzleDependencyAge::class.java)
    private const val TIMEOUT_MILLIS = 2_000
    private val centralTimestampRepository = RemoteRepository.Builder(
      "central-timestamp",
      "default",
      "https://repo1.maven.org/maven2/"
    ).build()

    fun minimumAgeHours(property: String?, environment: String?): Int {
      val raw = property ?: environment ?: "48"
      return raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw GradleException(
          "muzzleMinDependencyAgeHours / MIN_DEPENDENCY_AGE_HOURS must be a non-negative integer"
        )
    }

    /** Read the POM's Last-Modified header with short best-effort timeouts and no retries. */
    private fun readTimestamp(url: String): Timestamp {
      var connection: URLConnection? = null
      try {
        connection = URL(url).openConnection().apply {
          connectTimeout = TIMEOUT_MILLIS
          readTimeout = TIMEOUT_MILLIS
          useCaches = false
        }
        if (connection is HttpURLConnection) {
          connection.requestMethod = "HEAD"
          val status = connection.responseCode
          if (status !in 200..299) {
            return Timestamp(null, "HTTP $status")
          }
        } else {
          connection.connect()
        }
        val modifiedAt = connection.getHeaderFieldDate("Last-Modified", -1L)
        return if (modifiedAt >= 0) {
          Timestamp(Instant.ofEpochMilli(modifiedAt))
        } else {
          Timestamp(null, "missing or invalid Last-Modified header")
        }
      } finally {
        if (connection is HttpURLConnection) connection.disconnect()
      }
    }
  }
}

/** Keeps timestamp caching scoped to a build, including when a Gradle daemon is reused. */
internal abstract class MuzzleDependencyAgeService : BuildService<MuzzleDependencyAgeService.Parameters> {
  interface Parameters : BuildServiceParameters {
    val minimumAgeHours: Property<Int>
  }

  val age: MuzzleDependencyAge by lazy {
    MuzzleDependencyAge(parameters.minimumAgeHours.get(), Instant.now())
  }
}

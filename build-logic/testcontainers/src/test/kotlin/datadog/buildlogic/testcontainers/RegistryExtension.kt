package datadog.buildlogic.testcontainers

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.InetAddress
import java.security.MessageDigest

/** A local HTTPS registry with mutable manifests. */
class RegistryExtension :
  BeforeEachCallback,
  AfterEachCallback {
  private val server = MockWebServer()

  @Volatile var imageVersion = 1

  @Volatile var unavailable = false

  val image: String
    get() = "127.0.0.1:${server.port}/library/cassandra:4"

  val requestCount: Int
    get() = server.requestCount

  val digest: String
    get() = digest(manifest())

  override fun beforeEach(context: ExtensionContext) {
    val certificate =
      HeldCertificate
        .Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
    val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.setDispatcher(
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when {
            unavailable -> {
              MockResponse().setResponseCode(503)
            }

            !request.path!!.startsWith("/v2/") -> {
              MockResponse().setResponseCode(404)
            }

            else -> {
              val body = manifest()
              MockResponse()
                .setHeader("Content-Type", "application/vnd.oci.image.manifest.v1+json")
                .setHeader("Docker-Content-Digest", digest(body))
                .setBody(body)
            }
          }
      },
    )
    server.start(InetAddress.getByName("127.0.0.1"), 0)
  }

  override fun afterEach(context: ExtensionContext) {
    server.shutdown()
  }

  private fun manifest() =
    """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${digest(
      imageVersion.toString(),
    )}","size":2},"layers":[]}"""

  private fun digest(body: String) =
    "sha256:" + MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
}

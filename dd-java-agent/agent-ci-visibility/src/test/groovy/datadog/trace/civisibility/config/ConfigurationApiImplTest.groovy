package datadog.trace.civisibility.config

import com.squareup.moshi.Moshi
import datadog.communication.http.HttpRetryPolicy
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.civisibility.communication.BackendApi
import datadog.trace.civisibility.communication.EvpProxyApi
import okhttp3.HttpUrl
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class ConfigurationApiImplTest extends Specification {

  private static final int REQUEST_TIMEOUT_MILLIS = 15_000

  @Shared
  Moshi moshi = new Moshi.Builder().build()

  @Shared
  @AutoCleanup
  TestHttpServer intakeServer = httpServer {
    handlers {
      prefix("/api/v2/libraries/tests/services/setting") {
        def requestJson = moshi.adapter(Map).fromJson(new String(request.body))
        boolean expectedRequest = requestJson == [
          "data": [
            "type"      : "ci_app_test_service_libraries_settings",
            "id"        : "1234",
            "attributes": [
              "service"       : "foo",
              "env"           : "foo_env",
              "repository_url": "https://github.com/DataDog/foo",
              "branch"        : "prod",
              "sha"           : "d64185e45d1722ab3a53c45be47accae",
              "configurations": [
                "os.platform"         : "linux",
                "os.architecture"     : "amd64",
                "os.arch"             : "amd64",
                "os.version"          : "bionic",
                "runtime.name"        : "runtimeName",
                "runtime.version"     : "runtimeVersion",
                "runtime.vendor"      : "vendor",
                "runtime.architecture": "amd64"
              ]
            ]
          ]
        ]

        if (expectedRequest) {
          response.status(200).send('{ "data": { "type": "ci_app_tracers_test_service_settings", "id": "uuid", "attributes": { "code_coverage": true, "tests_skipping": true } } }')
        } else {
          response.status(400).send()
        }
      }
    }
  }

  def "test settings request"() {
    given:
    def evpProxy = givenEvpProxy()
    def tracerEnvironment = givenTracerEnvironment()

    when:
    def settingsApi = new ConfigurationApiImpl(evpProxy, () -> "1234")
    def settings = settingsApi.getSettings(tracerEnvironment)

    then:
    settings.codeCoverageEnabled
    settings.testsSkippingEnabled
  }

  private BackendApi givenEvpProxy() {
    HttpUrl proxyUrl = HttpUrl.get(intakeServer.address)
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0)
    return new EvpProxyApi(proxyUrl, REQUEST_TIMEOUT_MILLIS, retryPolicyFactory)
  }

  private static TracerEnvironment givenTracerEnvironment() {
    return TracerEnvironment.builder()
    .service("foo")
    .env("foo_env")
    .repositoryUrl("https://github.com/DataDog/foo")
    .branch("prod")
    .sha("d64185e45d1722ab3a53c45be47accae")
    .osPlatform("linux")
    .osArchitecture("amd64")
    .osVersion("bionic")
    .runtimeName("runtimeName")
    .runtimeVersion("runtimeVersion")
    .runtimeVendor("vendor")
    .runtimeArchitecture("amd64")
    .build()
  }
}

package datadog.trace.civisibility.config

import com.squareup.moshi.Moshi
import datadog.communication.BackendApi
import datadog.communication.BackendApiFactory
import datadog.communication.EvpProxyApi
import datadog.communication.IntakeApi
import datadog.communication.http.HttpRetryPolicy
import datadog.communication.http.OkHttpUtils
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.civisibility.config.Configurations
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.apache.commons.io.IOUtils
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

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
              "test_level"    : "test",
              "configurations": [
                "os.platform"         : "linux",
                "os.architecture"     : "amd64",
                "os.arch"             : "amd64",
                "os.version"          : "bionic",
                "runtime.name"        : "runtimeName",
                "runtime.version"     : "runtimeVersion",
                "runtime.vendor"      : "vendor",
                "runtime.architecture": "amd64",
                "custom"              : [
                  "customTag": "customValue"
                ]
              ]
            ]
          ]
        ]

        def response = response
        if (expectedRequest) {
          def requestBody = ('{' +
            '  "data": {' +
            '    "type": "ci_app_tracers_test_service_settings",' +
            '    "id": "uuid",' +
            '    "attributes": {' +
            '      "code_coverage": true,' +
            '      "tests_skipping": true,' +
            '      "require_git": true,' +
            '      "flaky_test_retries_enabled": true,' +
            '      "early_flake_detection": {' +
            '        "enabled": true,' +
            '        "slow_test_retries": {' +
            '          "5s": 10,' +
            '          "10s": 5,' +
            '          "30s": 3,' +
            '          "5m": 2' +
            '        },' +
            '        "faulty_session_threshold": 30' +
            '      }' +
            '    }' +
            '  }' +
            '}').bytes

          def header = request.getHeader("Accept-Encoding")
          def gzipSupported = header != null && header.contains("gzip")
          if (gzipSupported) {
            response.addHeader("Content-Encoding", "gzip")
            requestBody = gzip(requestBody)
          }

          response.status(200).send(requestBody)
        } else {
          response.status(400).send()
        }
      }

      prefix("/api/v2/ci/tests/skippable") {
        def requestJson = moshi.adapter(Map).fromJson(new String(request.body))
        boolean expectedRequest = requestJson == [
          "data": [
            "type"      : "test_params",
            "id"        : "1234",
            "attributes": [
              "service"       : "foo",
              "env"           : "foo_env",
              "repository_url": "https://github.com/DataDog/foo",
              "branch"        : "prod",
              "sha"           : "d64185e45d1722ab3a53c45be47accae",
              "test_level"    : "test",
              "configurations": [
                "os.platform"         : "linux",
                "os.architecture"     : "amd64",
                "os.arch"             : "amd64",
                "os.version"          : "bionic",
                "runtime.name"        : "runtimeName",
                "runtime.version"     : "runtimeVersion",
                "runtime.vendor"      : "vendor",
                "runtime.architecture": "amd64",
                "custom"              : [
                  "customTag": "customValue"
                ]
              ]
            ]
          ]
        ]

        def response = response
        if (expectedRequest) {
          def requestBody = """
{
  "data": [
    {
      "id": "49968354e2091cdb",
      "type": "test",
      "attributes": {
        "configurations": {
          "test.bundle": "testBundle-a",
          "custom": {
            "customTag": "customValue"
          }
        },
        "suite": "suite-a",
        "name": "name-a",
        "parameters": "parameters-a"
      }
    },
    {
      "id": "49968354e2091cdc",
      "type": "test",
      "attributes": {
        "configurations": {
          "test.bundle": "testBundle-b",
          "custom": {
            "customTag": "customValue"
          }
        },
        "suite": "suite-b",
        "name": "name-b",
        "parameters": "parameters-b"
      }
    }
  ],
  "meta": {
    "correlation_id": "11223344",
    "coverage": {
        "src/main/java/Calculator.java": "/8AA/w==",
        "src/main/java/utils/Math.java": "AAAAf+AA/A==",
        "src/test/java/CalculatorTest.java": "//AAeAAA/A=="
    }
  }
}
""".bytes

          def header = request.getHeader("Accept-Encoding")
          def gzipSupported = header != null && header.contains("gzip")
          if (gzipSupported) {
            response.addHeader("Content-Encoding", "gzip")
            requestBody = gzip(requestBody)
          }

          response.status(200).send(requestBody)
        } else {
          response.status(400).send()
        }
      }

      prefix("/api/v2/ci/libraries/tests") {
        def requestJson = moshi.adapter(Map).fromJson(new String(request.body))
        boolean expectedRequest = requestJson == [
          "data": [
            "type"      : "ci_app_libraries_tests_request",
            "id"        : "1234",
            "attributes": [
              "service"       : "foo",
              "env"           : "foo_env",
              "repository_url": "https://github.com/DataDog/foo",
              "branch"        : "prod",
              "sha"           : "d64185e45d1722ab3a53c45be47accae",
              "test_level"    : "test",
              "configurations": [
                "os.platform"         : "linux",
                "os.architecture"     : "amd64",
                "os.arch"             : "amd64",
                "os.version"          : "bionic",
                "runtime.name"        : "runtimeName",
                "runtime.version"     : "runtimeVersion",
                "runtime.vendor"      : "vendor",
                "runtime.architecture": "amd64",
                "custom"              : [
                  "customTag": "customValue"
                ]
              ]
            ]
          ]
        ]

        def response = response
        if (expectedRequest) {
          def requestBody = ("""
{
    "data": {
        "id": "9p1jTQLXB8g",
        "type": "ci_app_libraries_tests",
        "attributes": {
            "tests": {
                "test-bundle-a": {
                    "test-suite-a": [
                        "test-name-1",
                        "test-name-2"
                    ],
                    "test-suite-b": [
                        "another-test-name-1",
                        "test-name-2"
                    ]
                },
                "test-bundle-N": {
                    "test-suite-M": [
                        "test-name-1",
                        "test-name-2"
                    ]
                }
            }
        }
    }
}
          """).bytes

          def header = request.getHeader("Accept-Encoding")
          def gzipSupported = header != null && header.contains("gzip")
          if (gzipSupported) {
            response.addHeader("Content-Encoding", "gzip")
            requestBody = gzip(requestBody)
          }

          response.status(200).send(requestBody)
        } else {
          response.status(400).send()
        }
      }
    }
  }

  private static byte[] gzip(byte[] payload) {
    def baos = new ByteArrayOutputStream()
    try (GZIPOutputStream zip = new GZIPOutputStream(baos)) {
      IOUtils.copy(new ByteArrayInputStream(payload), zip)
    }
    return baos.toByteArray()
  }

  def "test settings request: #displayName"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()
    def metricCollector = Stub(CiVisibilityMetricCollector)

    when:
    def configurationApi = new ConfigurationApiImpl(api, metricCollector, () -> "1234")
    def settings = configurationApi.getSettings(tracerEnvironment)

    then:
    settings.codeCoverageEnabled
    settings.testsSkippingEnabled
    settings.gitUploadRequired
    settings.flakyTestRetriesEnabled
    settings.earlyFlakeDetectionSettings.enabled
    settings.earlyFlakeDetectionSettings.faultySessionThreshold == 30
    settings.earlyFlakeDetectionSettings.getExecutions(TimeUnit.SECONDS.toMillis(3)) == 10
    settings.earlyFlakeDetectionSettings.getExecutions(TimeUnit.SECONDS.toMillis(9)) == 5
    settings.earlyFlakeDetectionSettings.getExecutions(TimeUnit.SECONDS.toMillis(29)) == 3
    settings.earlyFlakeDetectionSettings.getExecutions(TimeUnit.MINUTES.toMillis(4)) == 2
    settings.earlyFlakeDetectionSettings.getExecutions(TimeUnit.MINUTES.toMillis(6)) == 0

    where:
    api                   | displayName
    givenEvpProxy(false)  | "EVP proxy, compression disabled"
    givenEvpProxy(true)   | "EVP proxy, compression enabled"
    givenIntakeApi(false) | "intake, compression disabled"
    givenIntakeApi(true)  | "intake, compression enabled"
  }

  def "test skippable tests request: #displayName"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()
    def metricCollector = Stub(CiVisibilityMetricCollector)

    when:
    def configurationApi = new ConfigurationApiImpl(api, metricCollector, () -> "1234")
    def skippableTests = configurationApi.getSkippableTests(tracerEnvironment)

    then:
    skippableTests.identifiers == [
      new TestIdentifier("suite-a", "name-a", "parameters-a",
      new Configurations(null, null, null, null, null,
      null, null, "testBundle-a", Collections.singletonMap("customTag", "customValue"))),
      new TestIdentifier("suite-b", "name-b", "parameters-b",
      new Configurations(null, null, null, null, null,
      null, null, "testBundle-b", Collections.singletonMap("customTag", "customValue")))
    ]

    skippableTests.correlationId == "11223344"

    skippableTests.coveredLinesByRelativeSourcePath.size() == 3
    skippableTests.coveredLinesByRelativeSourcePath["src/main/java/Calculator.java"] == bits(0, 1, 2, 3, 4, 5, 6, 7, 14, 15, 24, 25, 26, 27, 28, 29, 30, 31)
    skippableTests.coveredLinesByRelativeSourcePath["src/test/java/CalculatorTest.java"] == bits(0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15, 27, 28, 29, 30, 50, 51, 52, 53, 54, 55)
    skippableTests.coveredLinesByRelativeSourcePath["src/main/java/utils/Math.java"] == bits(24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 50, 51, 52, 53, 54, 55)

    where:
    api                   | displayName
    givenEvpProxy(false)  | "EVP proxy, compression disabled"
    givenEvpProxy(true)   | "EVP proxy, compression enabled"
    givenIntakeApi(false) | "intake, compression disabled"
    givenIntakeApi(true)  | "intake, compression enabled"
  }

  private BitSet bits(int... bits) {
    BitSet bitSet = new BitSet()
    for (int bit : bits) {
      bitSet.set(bit)
    }
    return bitSet
  }

  def "test known tests request: #displayName"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()

    when:
    def configurationApi = new ConfigurationApiImpl(api, Stub(CiVisibilityMetricCollector), () -> "1234")
    def knownTests = configurationApi.getKnownTestsByModuleName(tracerEnvironment)


    then:
    knownTests == [
      "test-bundle-a": [
        new TestIdentifier("test-suite-a", "test-name-1", null, null),
        new TestIdentifier("test-suite-a", "test-name-2", null, null),
        new TestIdentifier("test-suite-b", "another-test-name-1", null, null),
        new TestIdentifier("test-suite-b", "test-name-2", null, null)
      ],
      "test-bundle-N": [
        new TestIdentifier("test-suite-M", "test-name-1", null, null),
        new TestIdentifier("test-suite-M", "test-name-2", null, null)
      ]
    ]

    where:
    api                   | displayName
    givenEvpProxy(false)  | "EVP proxy, response compression disabled"
    givenEvpProxy(true)   | "EVP proxy, response compression enabled"
    givenIntakeApi(false) | "intake, response compression disabled"
    givenIntakeApi(true)  | "intake, response compression enabled"
  }

  private BackendApi givenEvpProxy(boolean responseCompression) {
    String traceId = "a-trace-id"
    HttpUrl proxyUrl = HttpUrl.get(intakeServer.address)
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0)
    OkHttpClient client = OkHttpUtils.buildHttpClient(proxyUrl, REQUEST_TIMEOUT_MILLIS)
    return new EvpProxyApi(traceId, proxyUrl, retryPolicyFactory, client, responseCompression)
  }

  private BackendApi givenIntakeApi(boolean responseCompression) {
    HttpUrl intakeUrl = HttpUrl.get(String.format("%s/api/%s/", intakeServer.address.toString(), BackendApiFactory.Intake.API.version))

    String apiKey = "api-key"
    String traceId = "a-trace-id"
    long timeoutMillis = 1000

    HttpRetryPolicy retryPolicy = Stub(HttpRetryPolicy)
    retryPolicy.shouldRetry(_) >> false

    HttpRetryPolicy.Factory retryPolicyFactory = Stub(HttpRetryPolicy.Factory)
    retryPolicyFactory.create() >> retryPolicy

    return new IntakeApi(intakeUrl, apiKey, traceId, timeoutMillis, retryPolicyFactory, responseCompression)
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
    .customTag("customTag", "customValue")
    .build()
  }
}

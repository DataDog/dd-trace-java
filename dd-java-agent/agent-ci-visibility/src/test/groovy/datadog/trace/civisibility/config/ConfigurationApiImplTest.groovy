package datadog.trace.civisibility.config

import datadog.communication.BackendApi
import datadog.communication.EvpProxyApi
import datadog.communication.IntakeApi
import datadog.communication.http.HttpRetryPolicy
import datadog.communication.http.HttpUtils
import datadog.http.client.HttpClient
import datadog.http.client.HttpUrl
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestMetadata
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.intake.Intake
import freemarker.core.Environment
import freemarker.core.InvalidReferenceException
import freemarker.template.Template
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.io.IOUtils
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import spock.lang.Specification

import java.util.function.Function
import java.util.zip.GZIPOutputStream

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class ConfigurationApiImplTest extends Specification {

  private static final int REQUEST_TIMEOUT_MILLIS = 15_000

  private static final String REQUEST_UID = "1234"

  def "test settings request"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()

    def intakeServer = givenBackendEndpoint(
    "/api/v2/libraries/tests/services/setting",
    "/datadog/trace/civisibility/config/settings-request.ftl",
    [uid: REQUEST_UID, tracerEnvironment: tracerEnvironment],
    "/datadog/trace/civisibility/config/settings-response.ftl",
    [settings: expectedSettings]
    )

    def configurationApi = givenConfigurationApi(intakeServer, agentless, compression)

    when:
    def settings = configurationApi.getSettings(tracerEnvironment)

    then:
    settings == expectedSettings

    cleanup:
    intakeServer.close()

    where:
    agentless | compression | expectedSettings
    false     | false       | new CiVisibilitySettings(false, false, false, false, false, false, false, false, false, EarlyFlakeDetectionSettings.DEFAULT, TestManagementSettings.DEFAULT, null)
    false     | true        | new CiVisibilitySettings(true, true, true, true, true, true, true, true, true, EarlyFlakeDetectionSettings.DEFAULT, TestManagementSettings.DEFAULT, "main")
    true      | false       | new CiVisibilitySettings(false, true, false, true, false, true, false, false, true, new EarlyFlakeDetectionSettings(true, [new ExecutionsByDuration(1000, 3)], 10), new TestManagementSettings(true, 10), "master")
    true      | true        | new CiVisibilitySettings(false, false, true, true, false, false, true, true, false, new EarlyFlakeDetectionSettings(true, [new ExecutionsByDuration(5000, 3), new ExecutionsByDuration(120000, 2)], 10), new TestManagementSettings(true, 20), "prod")
  }

  def "test skippable tests request"() {
    given:
    def tracerEnvironment = givenTracerEnvironment(testBundle)

    def intakeServer = givenBackendEndpoint(
    "/api/v2/ci/tests/skippable",
    "/datadog/trace/civisibility/config/${request}",
    [uid: REQUEST_UID, tracerEnvironment: tracerEnvironment],
    "/datadog/trace/civisibility/config/${response}",
    [:]
    )

    def configurationApi = givenConfigurationApi(intakeServer)

    when:
    def skippableTests = configurationApi.getSkippableTests(tracerEnvironment)

    then:
    skippableTests.identifiersByModule == expectedTests

    skippableTests.correlationId == "11223344"

    skippableTests.coveredLinesByRelativeSourcePath.size() == 3
    skippableTests.coveredLinesByRelativeSourcePath["src/main/java/Calculator.java"] == bits(0, 1, 2, 3, 4, 5, 6, 7, 14, 15, 24, 25, 26, 27, 28, 29, 30, 31)
    skippableTests.coveredLinesByRelativeSourcePath["src/test/java/CalculatorTest.java"] == bits(0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15, 27, 28, 29, 30, 50, 51, 52, 53, 54, 55)
    skippableTests.coveredLinesByRelativeSourcePath["src/main/java/utils/Math.java"] == bits(24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 50, 51, 52, 53, 54, 55)

    cleanup:
    intakeServer.close()

    where:
    testBundle     | request                            | response                            | expectedTests
    null           | "skippable-request.ftl"            | "skippable-response.ftl"            | [
      "testBundle-a": [(new TestIdentifier("suite-a", "name-a", "parameters-a")): new TestMetadata(true)],
      "testBundle-b": [(new TestIdentifier("suite-b", "name-b", null)): new TestMetadata(false)]
    ]
    "testBundle-a" | "skippable-request-one-module.ftl" | "skippable-response-one-module.ftl" | [
      "testBundle-a": [
        (new TestIdentifier("suite-a", "name-a", "parameters-a")): new TestMetadata(true),
        (new TestIdentifier("suite-b", "name-b", null))          : new TestMetadata(true)
      ],
    ]
  }

  def "test flaky tests request"() {
    given:
    def tracerEnvironment = givenTracerEnvironment(testBundle)

    def intakeServer = givenBackendEndpoint(
    "/api/v2/ci/libraries/tests/flaky",
    "/datadog/trace/civisibility/config/${request}",
    [uid: REQUEST_UID, tracerEnvironment: tracerEnvironment],
    "/datadog/trace/civisibility/config/${response}",
    [:]
    )

    def configurationApi = givenConfigurationApi(intakeServer)

    when:
    def flakyTests = configurationApi.getFlakyTestsByModule(tracerEnvironment)

    then:
    flakyTests == expectedTests

    cleanup:
    intakeServer.close()

    where:
    testBundle     | request                        | response                        | expectedTests
    null           | "flaky-request.ftl"            | "flaky-response.ftl"            | [
      "testBundle-a": new HashSet<>([new TestFQN("suite-a", "name-a")]),
      "testBundle-b": new HashSet<>([new TestFQN("suite-b", "name-b")]),
    ]
    "testBundle-a" | "flaky-request-one-module.ftl" | "flaky-response-one-module.ftl" | [
      "testBundle-a": new HashSet<>([new TestFQN("suite-a", "name-a")])
    ]
  }

  def "test known tests request"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()
    def pageInfo = [:]

    def intakeServer = givenBackendEndpoint(
    "/api/v2/ci/libraries/tests",
    "/datadog/trace/civisibility/config/known-tests-request.ftl",
    [uid: REQUEST_UID, tracerEnvironment: tracerEnvironment, pageInfo: pageInfo],
    "/datadog/trace/civisibility/config/known-tests-response.ftl",
    [:]
    )

    def configurationApi = givenConfigurationApi(intakeServer)

    when:
    def knownTests = configurationApi.getKnownTestsByModule(tracerEnvironment)

    for (Map.Entry<String, Collection<TestFQN>> e : knownTests.entrySet()) {
      def sortedTests = new ArrayList<>(e.value)
      Collections.sort(sortedTests, Comparator.comparing(TestFQN::getSuite).thenComparing((Function)TestFQN::getName))
      e.value = sortedTests
    }

    then:
    knownTests == [
      "test-bundle-a": [
        new TestFQN("test-suite-a", "test-name-1"),
        new TestFQN("test-suite-a", "test-name-2"),
        new TestFQN("test-suite-b", "another-test-name-1"),
        new TestFQN("test-suite-b", "test-name-2")
      ],
      "test-bundle-N": [
        new TestFQN("test-suite-M", "test-name-1"),
        new TestFQN("test-suite-M", "test-name-2")
      ]
    ]

    cleanup:
    intakeServer.close()
  }

  def "test known tests request with pagination"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()
    def requestCount = new AtomicInteger(0)

    // Define page responses
    def page1 = '{"data":{"id":"page1","type":"ci_app_libraries_tests","attributes":{"tests":{"module-a":{"suite-a":["test-1","test-2"]}},"page_info":{"size":2,"has_next":true,"cursor":"cursor-page-2"}}}}'
    def page2 = '{"data":{"id":"page2","type":"ci_app_libraries_tests","attributes":{"tests":{"module-b":{"suite-b":["test-3","test-4"]}},"page_info":{"size":2,"has_next":true,"cursor":"cursor-page-3"}}}}'
    def page3 = '{"data":{"id":"page3","type":"ci_app_libraries_tests","attributes":{"tests":{"module-c":{"suite-c":["test-5","test-6"]}},"page_info":{"size":2,"has_next":false}}}}'
    def responses = [page1, page2, page3]

    def intakeServer = httpServer {
      handlers {
        prefix("/api/v2/ci/libraries/tests") {
          def currentRequest = requestCount.incrementAndGet()
          def responseBody = responses[currentRequest - 1].bytes

          def response = response
          def header = request.getHeader("Accept-Encoding")
          def gzipSupported = header != null && header.contains("gzip")
          if (gzipSupported) {
            response.addHeader("Content-Encoding", "gzip")
            responseBody = gzip(responseBody)
          }

          response.status(200).send(responseBody)
        }
      }
    }
    def configurationApi = givenConfigurationApi(intakeServer)

    when:
    def knownTests = configurationApi.getKnownTestsByModule(tracerEnvironment)

    for (Map.Entry<String, Collection<TestFQN>> e : knownTests.entrySet()) {
      def sortedTests = new ArrayList<>(e.value)
      Collections.sort(sortedTests, Comparator.comparing(TestFQN::getSuite).thenComparing((Function)TestFQN::getName))
      e.value = sortedTests
    }

    then:
    // Verify 3 requests were made (3 pages)
    requestCount.get() == 3

    // Verify merged results from all 3 pages
    knownTests == [
      "module-a": [new TestFQN("suite-a", "test-1"), new TestFQN("suite-a", "test-2")],
      "module-b": [new TestFQN("suite-b", "test-3"), new TestFQN("suite-b", "test-4")],
      "module-c": [new TestFQN("suite-c", "test-5"), new TestFQN("suite-c", "test-6")]
    ]

    cleanup:
    intakeServer.close()
  }

  def "test test management tests request"() {
    given:
    def tracerEnvironment = givenTracerEnvironment()

    def intakeServer = givenBackendEndpoint(
    "/api/v2/test/libraries/test-management/tests",
    "/datadog/trace/civisibility/config/test-management-tests-request.ftl",
    [uid: REQUEST_UID, tracerEnvironment: tracerEnvironment],
    "/datadog/trace/civisibility/config/test-management-tests-response.ftl",
    [:]
    )

    def configurationApi = givenConfigurationApi(intakeServer)

    when:
    def testManagementTests = configurationApi.getTestManagementTestsByModule(tracerEnvironment, tracerEnvironment.getSha(), tracerEnvironment.getCommitMessage())
    def quarantinedTests = testManagementTests.get(TestSetting.QUARANTINED)
    def disabledTests = testManagementTests.get(TestSetting.DISABLED)
    def attemptToFixTests = testManagementTests.get(TestSetting.ATTEMPT_TO_FIX)

    then:
    quarantinedTests == [
      "module-a": new HashSet<>([new TestFQN("suite-a", "test-a"), new TestFQN("suite-b", "test-c")]),
      "module-b": new HashSet<>([new TestFQN("suite-c", "test-e")])
    ]
    disabledTests == [
      "module-a": new HashSet<>([new TestFQN("suite-a", "test-b")]),
      "module-b": new HashSet<>([new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-f")])
    ]
    attemptToFixTests == [
      "module-a": new HashSet<>([new TestFQN("suite-b", "test-c")]),
      "module-b": new HashSet<>([new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-e")])
    ]

    cleanup:
    intakeServer.close()
  }

  private ConfigurationApi givenConfigurationApi(TestHttpServer intakeServer, boolean agentless = true, boolean compression = true) {
    def api = agentless
    ? givenIntakeApi(intakeServer.address, compression)
    : givenEvpProxy(intakeServer.address, compression)
    return new ConfigurationApiImpl(api, Stub(CiVisibilityMetricCollector), () -> REQUEST_UID)
  }

  private TestHttpServer givenBackendEndpoint(String path,
  String requestTemplate,
  Map<String, Object> requestData,
  String responseTemplate,
  Map<String, Object> responseData) {
    httpServer {
      handlers {
        prefix(path) {
          def expectedRequestBody = getFreemarkerTemplate(requestTemplate, requestData)

          def response = response
          try {
            JSONAssert.assertEquals(expectedRequestBody, new String(request.body), JSONCompareMode.LENIENT)
          } catch (AssertionError error) {
            response.status(400).send(error.getMessage().bytes)
          }

          def responseBody = getFreemarkerTemplate(responseTemplate, responseData).bytes
          def header = request.getHeader("Accept-Encoding")
          def gzipSupported = header != null && header.contains("gzip")
          if (gzipSupported) {
            response.addHeader("Content-Encoding", "gzip")
            responseBody = gzip(responseBody)
          }

          response.status(200).send(responseBody)
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

  private BackendApi givenEvpProxy(URI address, boolean responseCompression) {
    String traceId = "a-trace-id"
    HttpUrl proxyUrl = HttpUrl.from(address)
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0)
    HttpClient client = HttpUtils.buildHttpClient(proxyUrl, REQUEST_TIMEOUT_MILLIS)
    return new EvpProxyApi(traceId, proxyUrl, "api", retryPolicyFactory, client, responseCompression)
  }

  private BackendApi givenIntakeApi(URI address, boolean responseCompression) {
    HttpUrl intakeUrl = HttpUrl.parse(String.format("%s/api/%s/", address.toString(), Intake.API.getVersion()))

    String apiKey = "api-key"
    String traceId = "a-trace-id"

    HttpRetryPolicy retryPolicy = Stub(HttpRetryPolicy)
    retryPolicy.shouldRetry(_) >> false

    HttpRetryPolicy.Factory retryPolicyFactory = Stub(HttpRetryPolicy.Factory)
    retryPolicyFactory.create() >> retryPolicy

    HttpClient client = HttpUtils.buildHttpClient(intakeUrl, REQUEST_TIMEOUT_MILLIS)
    return new IntakeApi(intakeUrl, apiKey, traceId, retryPolicyFactory, client, responseCompression)
  }

  private static TracerEnvironment givenTracerEnvironment(String testBundle = null) {
    return TracerEnvironment.builder()
    .service("foo")
    .env("foo_env")
    .repositoryUrl("https://github.com/DataDog/foo")
    .branch("prod")
    .sha("d64185e45d1722ab3a53c45be47accae")
    .commitMessage("full commit message")
    .osPlatform("linux")
    .osArchitecture("amd64")
    .osVersion("bionic")
    .runtimeName("runtimeName")
    .runtimeVersion("runtimeVersion")
    .runtimeVendor("vendor")
    .runtimeArchitecture("amd64")
    .customTag("customTag", "customValue")
    .testBundle(testBundle)
    .build()
  }

  private static BitSet bits(int ... bits) {
    BitSet bitSet = new BitSet()
    for (int bit : bits) {
      bitSet.set(bit)
    }
    return bitSet
  }

  static final TemplateExceptionHandler SUPPRESS_EXCEPTION_HANDLER = new TemplateExceptionHandler() {
    @Override
    void handleTemplateException(TemplateException e, Environment environment, Writer writer) throws TemplateException {
      if (e instanceof InvalidReferenceException) {
        writer.write('"<VALUE_MISSING>"')
      } else {
        throw e
      }
    }
  }

  static final freemarker.template.Configuration FREEMARKER = new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_30) { {
      setClassLoaderForTemplateLoading(ConfigurationApiImplTest.classLoader, "")
      setDefaultEncoding("UTF-8")
      setTemplateExceptionHandler(SUPPRESS_EXCEPTION_HANDLER)
      setLogTemplateExceptions(false)
      setWrapUncheckedExceptions(true)
      setFallbackOnNullLoopVariable(false)
      setNumberFormat("0.######")
    }
  }

  static String getFreemarkerTemplate(String templatePath, Map<String, Object> replacements, List<Map<?, ?>> replacementsSource = []) {
    try {
      Template coveragesTemplate = FREEMARKER.getTemplate(templatePath)
      StringWriter coveragesOut = new StringWriter()
      coveragesTemplate.process(replacements, coveragesOut)
      return coveragesOut.toString()
    } catch (Exception e) {
      throw new RuntimeException("Could not get Freemarker template " + templatePath + "; replacements map: " + replacements + "; replacements source: " + replacementsSource, e)
    }
  }
}

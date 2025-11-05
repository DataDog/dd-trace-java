package datadog.trace.civisibility.coverage.report

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.BackendApi
import datadog.communication.IntakeApi
import datadog.communication.http.HttpRetryPolicy
import datadog.communication.http.OkHttpUtils
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.intake.Intake
import datadog.trace.test.util.MultipartRequestParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.zip.GZIPInputStream

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class CoverageReportUploaderTest extends Specification {

  private static final int REQUEST_TIMEOUT_MILLIS = 15_000

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper()

  private static final String COVERAGE_REPORT_BODY = "report-body"
  private static final String JACOCO_FORMAT = "jacoco"
  private static final String CI_TAG_KEY = "ci-tag-key"
  private static final String CI_TAG_VALUE = "ci-tag-value"

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix('/api/v2/cicovreprt') {
        def parsed = MultipartRequestParser.parseRequest(request.body, request.headers.get("Content-Type"))

        def event = JSON_MAPPER.readValue(parsed["event"].get(0).get(), Map)
        if (event.size() != 3 || event["format"] != JACOCO_FORMAT  || event["type"] != "coverage_report" || event[CI_TAG_KEY] != CI_TAG_VALUE) {
          response.status(400).send("Incorrect event $event")
          return
        }

        def coverage = new String(gunzip(parsed["coverage"].get(0).get()))
        if (coverage != COVERAGE_REPORT_BODY) {
          response.status(400).send("Incorrect coverage $coverage")
          return
        }

        response.status(200).send()
      }
    }
  }

  static byte[] gunzip(byte[] compressed) throws IOException {
    try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed)
    GZIPInputStream gis = new GZIPInputStream(bais)
    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

      byte[] buffer = new byte[8192]
      int len
      while ((len = gis.read(buffer)) != -1) {
        baos.write(buffer, 0, len)
      }
      return baos.toByteArray()
    }
  }

  def "test upload coverage report"() {
    setup:
    def backendApi = givenIntakeApi()
    def metricCollector = Stub(CiVisibilityMetricCollector)
    def uploader = new CoverageReportUploader(backendApi, [(CI_TAG_KEY):CI_TAG_VALUE], metricCollector)
    def report = new ByteArrayInputStream(COVERAGE_REPORT_BODY.getBytes())

    expect:
    uploader.upload(JACOCO_FORMAT, report)
  }

  private BackendApi givenIntakeApi() {
    HttpUrl intakeUrl = HttpUrl.get(String.format("%s/api/%s/", server.address.toString(), Intake.CI_INTAKE.version))

    String apiKey = "api-key"
    String traceId = "a-trace-id"

    HttpRetryPolicy retryPolicy = Stub(HttpRetryPolicy)
    retryPolicy.shouldRetry(_) >> false

    HttpRetryPolicy.Factory retryPolicyFactory = Stub(HttpRetryPolicy.Factory)
    retryPolicyFactory.create() >> retryPolicy

    OkHttpClient client = OkHttpUtils.buildHttpClient(intakeUrl, REQUEST_TIMEOUT_MILLIS)
    return new IntakeApi(intakeUrl, apiKey, traceId, retryPolicyFactory, client, false)
  }
}

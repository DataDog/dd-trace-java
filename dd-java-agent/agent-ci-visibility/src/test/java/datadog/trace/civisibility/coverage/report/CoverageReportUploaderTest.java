package datadog.trace.civisibility.coverage.report;

import static datadog.trace.agent.test.server.http.JavaTestHttpServer.httpServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.BackendApi;
import datadog.communication.IntakeApi;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.intake.Intake;
import datadog.trace.test.util.MultipartRequestParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.apache.commons.fileupload.FileItem;
import org.junit.jupiter.api.Test;

class CoverageReportUploaderTest {

  private static final int REQUEST_TIMEOUT_MILLIS = 15_000;

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> EVENT_TYPE =
      new TypeReference<Map<String, Object>>() {};

  private static final String COVERAGE_REPORT_BODY = "report-body";
  private static final String JACOCO_FORMAT = "jacoco";
  private static final String CI_TAG_KEY = "ci-tag-key";
  private static final String CI_TAG_VALUE = "ci-tag-value";

  @Test
  void uploadsCoverageReportWithoutFlags() throws IOException {
    CapturedRequest request = uploadCoverageReport(Collections.emptyList());

    assertEquals(3, request.event.size());
    assertEquals(JACOCO_FORMAT, request.event.get("format"));
    assertEquals("coverage_report", request.event.get("type"));
    assertEquals(CI_TAG_VALUE, request.event.get(CI_TAG_KEY));
    assertFalse(request.event.containsKey("report.flags"));
    assertEquals(COVERAGE_REPORT_BODY, new String(request.coverage, StandardCharsets.UTF_8));
  }

  @Test
  void uploadsCoverageReportWithOrderedDuplicateFlags() throws IOException {
    List<String> flags = Arrays.asList("type:unit-tests", "jvm-21", "jvm-21");

    CapturedRequest request = uploadCoverageReport(flags);

    assertEquals(4, request.event.size());
    assertEquals(JACOCO_FORMAT, request.event.get("format"));
    assertEquals("coverage_report", request.event.get("type"));
    assertEquals(CI_TAG_VALUE, request.event.get(CI_TAG_KEY));
    assertEquals(flags, request.event.get("report.flags"));
    assertEquals(COVERAGE_REPORT_BODY, new String(request.coverage, StandardCharsets.UTF_8));
  }

  private static CapturedRequest uploadCoverageReport(List<String> flags) throws IOException {
    CapturedRequest capturedRequest = new CapturedRequest();
    try (JavaTestHttpServer server =
        httpServer(
            s ->
                s.handlers(
                    h ->
                        h.prefix(
                            "/api/v2/cicovreprt",
                            api -> {
                              Map<String, List<FileItem>> multipart =
                                  MultipartRequestParser.parseRequest(
                                      api.getRequest().getBody(),
                                      api.getRequest().getHeader("Content-Type"));
                              capturedRequest.event =
                                  JSON_MAPPER.readValue(
                                      multipart.get("event").get(0).get(), EVENT_TYPE);
                              capturedRequest.coverage =
                                  gunzip(multipart.get("coverage").get(0).get());
                              api.getResponse().status(200).send();
                            })))) {
      BackendApi backendApi = givenIntakeApi(server.getAddress());
      CoverageReportUploader uploader =
          new CoverageReportUploader(
              backendApi,
              Collections.singletonMap(CI_TAG_KEY, CI_TAG_VALUE),
              flags,
              NoOpMetricCollector.INSTANCE);

      uploader.upload(
          JACOCO_FORMAT,
          new ByteArrayInputStream(COVERAGE_REPORT_BODY.getBytes(StandardCharsets.UTF_8)));
    }
    return capturedRequest;
  }

  private static byte[] gunzip(byte[] compressed) throws IOException {
    try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
        GZIPInputStream gzip = new GZIPInputStream(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      for (int readCount; (readCount = gzip.read(buffer)) != -1; ) {
        output.write(buffer, 0, readCount);
      }
      return output.toByteArray();
    }
  }

  private static BackendApi givenIntakeApi(URI address) {
    HttpUrl intakeUrl =
        HttpUrl.get(String.format("%s/api/%s/", address, Intake.CI_INTAKE.getVersion()));
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);
    OkHttpClient client = OkHttpUtils.buildHttpClient(intakeUrl, REQUEST_TIMEOUT_MILLIS);
    return new IntakeApi(intakeUrl, "api-key", "a-trace-id", retryPolicyFactory, client, false);
  }

  private static final class CapturedRequest {
    private Map<String, Object> event;
    private byte[] coverage;
  }
}

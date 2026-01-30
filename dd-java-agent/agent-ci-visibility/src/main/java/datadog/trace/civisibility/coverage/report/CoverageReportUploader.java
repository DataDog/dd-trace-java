package datadog.trace.civisibility.coverage.report;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpRequestListener;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.communication.TelemetryListener;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import okio.BufferedSink;

public class CoverageReportUploader {

  private final BackendApi backendApi;
  private final Map<String, String> ciTags;
  private final CiVisibilityMetricCollector metricCollector;
  private final JsonAdapter<Map<String, String>> eventAdapter;

  public CoverageReportUploader(
      BackendApi backendApi,
      Map<String, String> ciTags,
      CiVisibilityMetricCollector metricCollector) {
    this.backendApi = backendApi;
    this.ciTags = ciTags;
    this.metricCollector = metricCollector;

    Moshi moshi = new Moshi.Builder().build();
    Type type = Types.newParameterizedType(Map.class, String.class, String.class);
    eventAdapter = moshi.adapter(type);
  }

  public void upload(String format, InputStream reportStream) throws IOException {
    Map<String, String> event = new HashMap<>(ciTags);
    event.put("format", format);
    event.put("type", "coverage_report");
    String eventJson = eventAdapter.toJson(event);
    RequestBody eventBody = HttpUtils.jsonRequestBodyOf(eventJson.getBytes(StandardCharsets.UTF_8));

    RequestBody coverageBody = new GzipMultipartRequestBody(reportStream);

    MultipartBody multipartBody =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("coverage", "coverage.gz", coverageBody)
            .addFormDataPart("event", "event.json", eventBody)
            .build();

    HttpRequestListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.COVERAGE_UPLOAD_REQUEST)
            .requestBytes(CiVisibilityDistributionMetric.COVERAGE_UPLOAD_REQUEST_BYTES)
            .requestErrors(CiVisibilityCountMetric.COVERAGE_UPLOAD_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.COVERAGE_UPLOAD_REQUEST_MS)
            .build();

    backendApi.post("cicovreprt", multipartBody, responseStream -> null, telemetryListener, false);
  }

  /** Request body that compresses a form data part */
  private static class GzipMultipartRequestBody extends RequestBody {
    private final InputStream stream;

    private GzipMultipartRequestBody(InputStream stream) {
      this.stream = stream;
    }

    @Override
    public long contentLength() {
      return -1;
    }

    @Override
    public MediaType contentType() {
      return null;
    }

    @SuppressFBWarnings("OS_OPEN_STREAM")
    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      GZIPOutputStream outputStream = new GZIPOutputStream(sink.outputStream());
      byte[] buffer = new byte[8192];
      for (int readCount; (readCount = stream.read(buffer)) != -1; ) {
        outputStream.write(buffer, 0, readCount);
      }
      outputStream.finish();
      // not closing output stream as it would close the underlying sink, which is managed by okhttp
    }
  }
}

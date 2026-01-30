package datadog.trace.common.writer.ddintake;

import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpResponse;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

public class TelemetryListener implements HttpRequestListener {

  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final Endpoint endpoint;
  private long callStartTimestamp;

  public TelemetryListener(Endpoint endpoint) {
    this.endpoint = endpoint;
  }

  @Override
  public void onRequestStart(HttpRequest request) {
    callStartTimestamp = System.currentTimeMillis();
    InstrumentationBridge.getMetricCollector()
        .add(
            CiVisibilityCountMetric.ENDPOINT_PAYLOAD_REQUESTS,
            1,
            endpoint,
            GZIP_ENCODING.equalsIgnoreCase(request.header(CONTENT_ENCODING_HEADER))
                ? RequestCompressed.TRUE
                : null);

    HttpRequestBody body = request.body();
    int byteCount = body == null ? 0 : (int) body.contentLength();
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, byteCount, endpoint);
  }

  @Override
  public void onRequestEnd(HttpRequest request, @Nullable HttpResponse response) {
    if (response != null) {
      if (!response.isSuccessful()) {
        int responseCode = response.code();
        InstrumentationBridge.getMetricCollector()
            .add(
                CiVisibilityCountMetric.ENDPOINT_PAYLOAD_REQUESTS_ERRORS,
                1,
                endpoint,
                ErrorType.from(responseCode),
                StatusCode.from(responseCode));
      }
    }
    onRequestEnd();
  }

  @Override
  public void onRequestFailure(HttpRequest request, IOException exception) {
    int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_REQUESTS_MS, durationMillis, endpoint);
    InstrumentationBridge.getMetricCollector()
        .add(
            CiVisibilityCountMetric.ENDPOINT_PAYLOAD_REQUESTS_ERRORS,
            1,
            endpoint,
            ErrorType.NETWORK);
    onRequestEnd();
  }

  void onRequestEnd() {
    int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_REQUESTS_MS, durationMillis, endpoint);
  }
}

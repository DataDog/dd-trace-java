package datadog.trace.civisibility.communication;

import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpResponse;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.ResponseCompressed;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import java.io.IOException;
import javax.annotation.Nullable;

public class TelemetryListener implements HttpRequestListener {

  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final CiVisibilityMetricCollector metricCollector;
  private final @Nullable CiVisibilityCountMetric requestCountMetric;
  private final @Nullable CiVisibilityCountMetric requestErrorsMetric;
  private final @Nullable CiVisibilityDistributionMetric requestBytesMetric;
  private final @Nullable CiVisibilityDistributionMetric requestDurationMetric;
  private final @Nullable CiVisibilityDistributionMetric responseBytesMetric;
  private long callStartTimestamp;

  private TelemetryListener(
      CiVisibilityMetricCollector metricCollector,
      @Nullable CiVisibilityCountMetric requestCountMetric,
      @Nullable CiVisibilityCountMetric requestErrorsMetric,
      @Nullable CiVisibilityDistributionMetric requestBytesMetric,
      @Nullable CiVisibilityDistributionMetric requestDurationMetric,
      @Nullable CiVisibilityDistributionMetric responseBytesMetric) {
    this.metricCollector = metricCollector;
    this.requestCountMetric = requestCountMetric;
    this.requestErrorsMetric = requestErrorsMetric;
    this.requestBytesMetric = requestBytesMetric;
    this.requestDurationMetric = requestDurationMetric;
    this.responseBytesMetric = responseBytesMetric;
  }

  @Override
  public void onRequestStart(HttpRequest request) {
    callStartTimestamp = System.currentTimeMillis();
    if (requestCountMetric != null) {
      metricCollector.add(
          requestCountMetric,
          1,
          GZIP_ENCODING.equalsIgnoreCase(request.header(CONTENT_ENCODING_HEADER))
              ? RequestCompressed.TRUE
              : null);
    }

    HttpRequestBody body;
    if (requestBytesMetric != null && (body = request.body()) != null) {
      metricCollector.add(requestBytesMetric, (int) body.contentLength());
    }
  }

  @Override
  public void onRequestEnd(HttpRequest request, @Nullable HttpResponse response) {
    if (response != null) {
      if (requestErrorsMetric != null) {
        if (!response.isSuccessful()) {
          int responseCode = response.code();
          metricCollector.add(
              requestErrorsMetric, 1, ErrorType.from(responseCode), StatusCode.from(responseCode));
        }
      }

      if (responseBytesMetric != null) {
        boolean responseCompressed =
            GZIP_ENCODING.equalsIgnoreCase(response.header(CONTENT_ENCODING_HEADER));
        try {
          int contentLength = Integer.parseInt(response.header("Content-Length"));
          metricCollector.add(
              responseBytesMetric,
              contentLength,
              responseCompressed ? ResponseCompressed.TRUE : null);
        } catch (NumberFormatException e) {
          metricCollector.add(responseBytesMetric, 0);
        }
      }
    }

    onRequestEnd();
  }

  @Override
  public void onRequestFailure(HttpRequest request, IOException exception) {
    onRequestEnd();
    if (requestErrorsMetric != null) {
      metricCollector.add(requestErrorsMetric, 1, ErrorType.NETWORK);
    }
  }

  void onRequestEnd() {
    if (requestDurationMetric != null) {
      int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
      metricCollector.add(requestDurationMetric, durationMillis);
    }
  }

  public static final class Builder {
    private final CiVisibilityMetricCollector metricCollector;
    private @Nullable CiVisibilityCountMetric requestCountMetric;
    private @Nullable CiVisibilityCountMetric requestErrorsMetric;
    private @Nullable CiVisibilityDistributionMetric requestBytesMetric;
    private @Nullable CiVisibilityDistributionMetric requestDurationMetric;
    private @Nullable CiVisibilityDistributionMetric responseBytesMetric;

    public Builder(CiVisibilityMetricCollector metricCollector) {
      this.metricCollector = metricCollector;
    }

    public Builder requestCount(@Nullable CiVisibilityCountMetric requestCountMetric) {
      this.requestCountMetric = requestCountMetric;
      return this;
    }

    public Builder requestErrors(@Nullable CiVisibilityCountMetric requestErrorsMetric) {
      this.requestErrorsMetric = requestErrorsMetric;
      return this;
    }

    public Builder requestBytes(@Nullable CiVisibilityDistributionMetric requestBytesMetric) {
      this.requestBytesMetric = requestBytesMetric;
      return this;
    }

    public Builder requestDuration(@Nullable CiVisibilityDistributionMetric requestDurationMetric) {
      this.requestDurationMetric = requestDurationMetric;
      return this;
    }

    public Builder responseBytes(@Nullable CiVisibilityDistributionMetric responseBytesMetric) {
      this.responseBytesMetric = responseBytesMetric;
      return this;
    }

    public HttpRequestListener build() {
      return new TelemetryListener(
          metricCollector,
          requestCountMetric,
          requestErrorsMetric,
          requestBytesMetric,
          requestDurationMetric,
          responseBytesMetric);
    }
  }
}

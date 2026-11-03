package datadog.trace.civisibility.communication;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.ResponseCompressed;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Response;

public class TelemetryListener extends OkHttpUtils.CustomListener {

  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final CiVisibilityMetricCollector metricCollector;
  private final @Nullable CiVisibilityCountMetric requestCountMetric;
  private final @Nullable CiVisibilityCountMetric requestErrorsMetric;
  private final @Nullable CiVisibilityDistributionMetric requestBytesMetric;
  private final @Nullable CiVisibilityDistributionMetric requestDurationMetric;
  private final @Nullable CiVisibilityDistributionMetric responseBytesMetric;
  private final TimeSource timeSource;
  private long callStartTimestamp;
  private long totalRequestDurationNanos;
  private boolean responseCompressed;

  private TelemetryListener(
      CiVisibilityMetricCollector metricCollector,
      @Nullable CiVisibilityCountMetric requestCountMetric,
      @Nullable CiVisibilityCountMetric requestErrorsMetric,
      @Nullable CiVisibilityDistributionMetric requestBytesMetric,
      @Nullable CiVisibilityDistributionMetric requestDurationMetric,
      @Nullable CiVisibilityDistributionMetric responseBytesMetric,
      TimeSource timeSource) {
    this.metricCollector = metricCollector;
    this.requestCountMetric = requestCountMetric;
    this.requestErrorsMetric = requestErrorsMetric;
    this.requestBytesMetric = requestBytesMetric;
    this.requestDurationMetric = requestDurationMetric;
    this.responseBytesMetric = responseBytesMetric;
    this.timeSource = timeSource;
  }

  public void callStart(Call call) {
    callStartTimestamp = timeSource.getNanoTicks();
    if (requestCountMetric != null) {
      metricCollector.add(
          requestCountMetric,
          1,
          GZIP_ENCODING.equalsIgnoreCase(call.request().header(CONTENT_ENCODING_HEADER))
              ? RequestCompressed.TRUE
              : null);
    }
  }

  public void requestBodyEnd(Call call, long byteCount) {
    if (requestBytesMetric != null) {
      metricCollector.add(requestBytesMetric, (int) byteCount);
    }
  }

  public void responseHeadersEnd(Call call, Response response) {
    if (requestErrorsMetric != null) {
      if (!response.isSuccessful()) {
        int responseCode = response.code();
        metricCollector.add(
            requestErrorsMetric, 1, ErrorType.from(responseCode), StatusCode.from(responseCode));
      }
    }
    responseCompressed = GZIP_ENCODING.equalsIgnoreCase(response.header(CONTENT_ENCODING_HEADER));
  }

  @Override
  public void responseBodyEnd(Call call, long byteCount) {
    if (responseBytesMetric != null) {
      metricCollector.add(
          responseBytesMetric,
          (int) byteCount,
          responseCompressed ? ResponseCompressed.TRUE : null);
    }
  }

  public void callEnd(Call call) {
    recordRequestDuration();
  }

  public void callFailed(Call call, IOException ioe) {
    recordRequestDuration();

    if (requestErrorsMetric != null) {
      metricCollector.add(requestErrorsMetric, 1, ErrorType.NETWORK);
    }
  }

  private void recordRequestDuration() {
    long durationNanos = timeSource.getNanoTicks() - callStartTimestamp;
    totalRequestDurationNanos += durationNanos;
    if (requestDurationMetric != null) {
      metricCollector.add(
          requestDurationMetric, (int) TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
  }

  /** Returns total call time, including failed attempts and excluding time between calls. */
  public int getTotalRequestDurationMillis() {
    return (int) TimeUnit.NANOSECONDS.toMillis(totalRequestDurationNanos);
  }

  public static final class Builder {
    private final CiVisibilityMetricCollector metricCollector;
    private final TimeSource timeSource;
    private @Nullable CiVisibilityCountMetric requestCountMetric;
    private @Nullable CiVisibilityCountMetric requestErrorsMetric;
    private @Nullable CiVisibilityDistributionMetric requestBytesMetric;
    private @Nullable CiVisibilityDistributionMetric requestDurationMetric;
    private @Nullable CiVisibilityDistributionMetric responseBytesMetric;

    public Builder(CiVisibilityMetricCollector metricCollector) {
      this(metricCollector, SystemTimeSource.INSTANCE);
    }

    public Builder(CiVisibilityMetricCollector metricCollector, TimeSource timeSource) {
      this.metricCollector = metricCollector;
      this.timeSource = timeSource;
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

    public TelemetryListener build() {
      return new TelemetryListener(
          metricCollector,
          requestCountMetric,
          requestErrorsMetric,
          requestBytesMetric,
          requestDurationMetric,
          responseBytesMetric,
          timeSource);
    }
  }
}

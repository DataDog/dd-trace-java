package datadog.trace.civisibility.communication;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.ResponseCompressed;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import java.io.IOException;
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
  private long callStartTimestamp;
  private boolean responseCompressed;

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

  public void callStart(Call call) {
    callStartTimestamp = System.currentTimeMillis();
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
    if (requestDurationMetric != null) {
      int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
      metricCollector.add(requestDurationMetric, durationMillis);
    }
  }

  public void callFailed(Call call, IOException ioe) {
    if (requestDurationMetric != null) {
      int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
      metricCollector.add(requestDurationMetric, durationMillis);
    }

    if (requestErrorsMetric != null) {
      metricCollector.add(requestErrorsMetric, 1, ErrorType.NETWORK);
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

    public OkHttpUtils.CustomListener build() {
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

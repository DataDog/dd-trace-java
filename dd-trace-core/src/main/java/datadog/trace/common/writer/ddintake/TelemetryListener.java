package datadog.trace.common.writer.ddintake;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import datadog.trace.api.civisibility.telemetry.tag.ErrorType;
import datadog.trace.api.civisibility.telemetry.tag.RequestCompressed;
import datadog.trace.api.civisibility.telemetry.tag.StatusCode;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Response;

public class TelemetryListener extends OkHttpUtils.CustomListener {

  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final Endpoint endpoint;
  private long callStartTimestamp;

  public TelemetryListener(Endpoint endpoint) {
    this.endpoint = endpoint;
  }

  public void callStart(Call call) {
    callStartTimestamp = System.currentTimeMillis();
    InstrumentationBridge.getMetricCollector()
        .add(
            CiVisibilityCountMetric.ENDPOINT_PAYLOAD_REQUESTS,
            1,
            endpoint,
            GZIP_ENCODING.equalsIgnoreCase(call.request().header(CONTENT_ENCODING_HEADER))
                ? RequestCompressed.TRUE
                : null);
  }

  public void requestBodyEnd(Call call, long byteCount) {
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_BYTES, (int) byteCount, endpoint);
  }

  public void responseHeadersEnd(Call call, Response response) {
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

  public void callEnd(Call call) {
    int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_REQUESTS_MS, durationMillis, endpoint);
  }

  public void callFailed(Call call, IOException ioe) {
    int durationMillis = (int) (System.currentTimeMillis() - callStartTimestamp);
    InstrumentationBridge.getMetricCollector()
        .add(CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_REQUESTS_MS, durationMillis, endpoint);
    InstrumentationBridge.getMetricCollector()
        .add(
            CiVisibilityCountMetric.ENDPOINT_PAYLOAD_REQUESTS_ERRORS,
            1,
            endpoint,
            ErrorType.NETWORK);
  }
}

package datadog.telemetry;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryHttpClient {

  private static final Logger log = LoggerFactory.getLogger(TelemetryHttpClient.class);

  private final OkHttpClient okHttpClient;

  public TelemetryHttpClient(OkHttpClient okHttpClient) {
    this.okHttpClient = okHttpClient;
  }

  public RequestStatus sendRequest(Request request) {
    try (Response response = okHttpClient.newCall(request).execute()) {
      switch (response.code()) {
        case 202:
          return RequestStatus.SUCCESS;

        case 404:
          return RequestStatus.ENDPOINT_ERROR;

        default:
          log.debug(
              "Telemetry Intake Service responded with: {} {}",
              response.code(),
              response.message());
          return RequestStatus.HTTP_ERROR;
      }
    } catch (IOException e) {
      log.warn("IOException on HTTP request to Telemetry Intake ServiceL {}", e.getMessage());
      return RequestStatus.HTTP_ERROR;
    }
  }
}

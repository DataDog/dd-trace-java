package datadog.telemetry;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClient {
  public static final String DD_TELEMETRY_REQUEST_TYPE = "DD-Telemetry-Request-Type";

  public enum Result {
    SUCCESS,
    FAILURE,
    NOT_FOUND
  }

  private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

  private final OkHttpClient httpClient;

  public HttpClient(OkHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public Result sendRequest(Request request) {
    String requestType = request.header(DD_TELEMETRY_REQUEST_TYPE);
    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() == 404) {
        log.debug("Telemetry endpoint is disabled, dropping {} message", requestType);
        return Result.NOT_FOUND;
      }
      if (!response.isSuccessful()) {
        log.debug(
            "Telemetry message {} failed with: {} {} ",
            requestType,
            response.code(),
            response.message());
        return Result.FAILURE;
      }
    } catch (IOException e) {
      log.debug("Telemetry message {} failed with exception: {}", requestType, e.toString());
      return Result.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully", requestType);
    return Result.SUCCESS;
  }
}

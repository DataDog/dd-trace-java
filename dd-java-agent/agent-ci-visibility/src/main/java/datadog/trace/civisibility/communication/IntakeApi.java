package datadog.trace.civisibility.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.civisibility.utils.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API for posting HTTP requests directly to backend, without the need for DD Agent */
public class IntakeApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(IntakeApi.class);

  private static final String API_VERSION = "v2";
  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final String DD_APPLICATION_KEY_HEADER = "dd-application-key";

  private final String apiKey;
  private final String applicationKey;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final HttpUrl hostUrl;
  private final OkHttpClient httpClient;

  public IntakeApi(
      String site,
      String apiKey,
      String applicationKey,
      long timeoutMillis,
      HttpRetryPolicy.Factory retryPolicyFactory) {
    this.apiKey = apiKey;
    this.applicationKey = applicationKey;
    this.retryPolicyFactory = retryPolicyFactory;

    final String ciVisibilityAgentlessUrlStr = Config.get().getCiVisibilityAgentlessUrl();
    if (ciVisibilityAgentlessUrlStr != null && !ciVisibilityAgentlessUrlStr.isEmpty()) {
      hostUrl = HttpUrl.get(String.format("%s/api/%s/", ciVisibilityAgentlessUrlStr, API_VERSION));
    } else {
      hostUrl = HttpUrl.get(String.format("https://api.%s/api/%s/", site, API_VERSION));
    }

    httpClient = OkHttpUtils.buildHttpClient(hostUrl, timeoutMillis);
  }

  @Override
  public <T> T post(
      String uri, RequestBody requestBody, IOThrowingFunction<InputStream, T> responseParser)
      throws IOException {
    HttpUrl url = hostUrl.resolve(uri);
    Request.Builder requestBuilder =
        new Request.Builder().url(url).post(requestBody).addHeader(DD_API_KEY_HEADER, apiKey);

    if (applicationKey != null) {
      requestBuilder.addHeader(DD_APPLICATION_KEY_HEADER, applicationKey);
    }

    Request request = requestBuilder.build();
    HttpRetryPolicy retryPolicy = retryPolicyFactory.create();
    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicy, request)) {
      if (response.isSuccessful()) {
        log.debug("Request to {} returned successful response: {}", uri, response.code());
        return responseParser.apply(response.body().byteStream());
      } else {
        throw new IOException(
            "Request to "
                + uri
                + " returned error response "
                + response.code()
                + ": "
                + response.message()
                + "; "
                + (response.body() != null ? response.body().string() : ""));
      }
    }
  }
}

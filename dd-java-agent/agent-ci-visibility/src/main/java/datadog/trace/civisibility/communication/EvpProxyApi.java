package datadog.trace.civisibility.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.civisibility.utils.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API that uses DD Agent as a proxy to post request to backend. */
public class EvpProxyApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(EvpProxyApi.class);

  private static final String API_VERSION = "v2";
  private static final String X_DATADOG_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";
  private static final String API_SUBDOMAIN = "api";

  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final HttpUrl evpProxyUrl;
  private final OkHttpClient httpClient;

  public EvpProxyApi(
      HttpUrl evpProxyUrl, long timeoutMillis, HttpRetryPolicy.Factory retryPolicyFactory) {
    this.evpProxyUrl = evpProxyUrl.resolve(String.format("api/%s/", API_VERSION));
    this.retryPolicyFactory = retryPolicyFactory;
    httpClient = OkHttpUtils.buildHttpClient(evpProxyUrl, timeoutMillis);
  }

  @Override
  public <T> T post(
      String uri, RequestBody requestBody, IOThrowingFunction<InputStream, T> responseParser)
      throws IOException {
    final HttpUrl url = evpProxyUrl.resolve(uri);
    final Request request =
        new Request.Builder()
            .url(url)
            .addHeader(X_DATADOG_EVP_SUBDOMAIN_HEADER, API_SUBDOMAIN)
            .post(requestBody)
            .build();

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

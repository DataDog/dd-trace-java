package datadog.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.HttpUtils;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.http.client.HttpResponse;
import datadog.communication.http.client.HttpUrl;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API that uses DD Agent as a proxy to post request to backend. */
public class EvpProxyApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(EvpProxyApi.class);

  private static final String API_VERSION = "v2";
  private static final String X_DATADOG_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";
  private static final String X_DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id";
  private static final String X_DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id";
  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final String traceId;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final HttpUrl evpProxyUrl;
  private final String subdomain;
  private final HttpClient httpClient;
  private final boolean responseCompression;

  public EvpProxyApi(
      String traceId,
      HttpUrl evpProxyUrl,
      String subdomain,
      HttpRetryPolicy.Factory retryPolicyFactory,
      HttpClient httpClient,
      boolean responseCompression) {
    this.traceId = traceId;
    this.evpProxyUrl = evpProxyUrl.resolve("api/" + API_VERSION + "/");
    this.subdomain = subdomain;
    this.retryPolicyFactory = retryPolicyFactory;
    this.httpClient = httpClient;
    this.responseCompression = responseCompression;
  }

  @Override
  public <T> T post(
      String uri,
      HttpRequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable HttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException {
    final HttpUrl url = evpProxyUrl.resolve(uri);

    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .url(url)
            .addHeader(X_DATADOG_EVP_SUBDOMAIN_HEADER, subdomain)
            .addHeader(X_DATADOG_TRACE_ID_HEADER, traceId)
            .addHeader(X_DATADOG_PARENT_ID_HEADER, traceId);

    if (requestListener != null) {
      // TODO: Add support for event listeners in abstract API
      // requestBuilder.tag(HttpUtils.CustomListener.class, requestListener);
    }

    if (requestCompression) {
      requestBuilder.addHeader(CONTENT_ENCODING_HEADER, GZIP_ENCODING);
    }

    if (responseCompression) {
      requestBuilder.addHeader(ACCEPT_ENCODING_HEADER, GZIP_ENCODING);
    }

    final HttpRequest request = requestBuilder.post(requestBody).build();

    try (HttpResponse response =
        HttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
      if (response.isSuccessful()) {
        log.debug("Request to {} returned successful response: {}", uri, response.code());

        InputStream responseBodyStream = response.body();

        String contentEncoding = response.header(CONTENT_ENCODING_HEADER);
        if (GZIP_ENCODING.equalsIgnoreCase(contentEncoding)) {
          log.debug("Response content encoding is {}, unzipping response body", contentEncoding);
          responseBodyStream = new GZIPInputStream(responseBodyStream);
        }

        return responseParser.apply(responseBodyStream);
      } else {
        String errorBody = "";
        try {
          InputStream errorStream = response.body();
          if (errorStream != null) {
            byte[] bytes = new byte[8192];
            int read = errorStream.read(bytes);
            if (read > 0) {
              errorBody = new String(bytes, 0, read);
            }
          }
        } catch (IOException e) {
          // Ignore errors reading error body
        }
        throw new IOException(
            "Request to "
                + uri
                + " returned error response "
                + response.code()
                + (errorBody.isEmpty() ? "" : "; " + errorBody));
      }
    }
  }
}

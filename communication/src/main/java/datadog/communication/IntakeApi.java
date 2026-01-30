package datadog.communication;

import static datadog.http.client.HttpRequest.CONTENT_TYPE;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.HttpUtils;
import datadog.communication.util.IOThrowingFunction;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API for posting HTTP requests directly to backend, without the need for DD Agent */
public class IntakeApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(IntakeApi.class);

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final String X_DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id";
  private static final String X_DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id";
  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final String apiKey;
  private final String traceId;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final boolean responseCompression;
  private final HttpUrl hostUrl;
  private final HttpClient httpClient;

  public IntakeApi(
      HttpUrl hostUrl,
      String apiKey,
      String traceId,
      HttpRetryPolicy.Factory retryPolicyFactory,
      HttpClient httpClient,
      boolean responseCompression) {
    this.hostUrl = hostUrl;
    this.apiKey = apiKey;
    this.traceId = traceId;
    this.retryPolicyFactory = retryPolicyFactory;
    this.responseCompression = responseCompression;
    this.httpClient = httpClient;
  }

  @Override
  public <T> T post(
      String uri,
      String contentType,
      HttpRequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable HttpRequestListener requestListener,
      boolean requestCompression)
      throws IOException {
    HttpUrl url = hostUrl.resolve(uri);
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .url(url)
            .post(requestBody)
            .addHeader(DD_API_KEY_HEADER, apiKey)
            .addHeader(X_DATADOG_TRACE_ID_HEADER, traceId)
            .addHeader(X_DATADOG_PARENT_ID_HEADER, traceId)
            .addHeader(CONTENT_TYPE, contentType)
            .listener(requestListener);

    if (requestCompression) {
      requestBuilder.addHeader(CONTENT_ENCODING_HEADER, GZIP_ENCODING);
    }

    if (responseCompression) {
      requestBuilder.addHeader(ACCEPT_ENCODING_HEADER, GZIP_ENCODING);
    }

    HttpRequest request = requestBuilder.build();
    try (HttpResponse response =
        HttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
      if (response.isSuccessful()) {
        log.debug("Request to {} returned successful response: {}", uri, response.code());
        try (InputStream responseBodyStream = streamFromResponse(response)) {
          return responseParser.apply(responseBodyStream);
        }
      } else {
        throw new IOException(
            "Request to "
                + uri
                + " returned error response "
                + response.code()
                + response.bodyAsString());
      }
    }
  }

  static InputStream streamFromResponse(HttpResponse response) throws IOException {
    InputStream responseBodyStream = response.body();
    String contentEncoding = response.header(CONTENT_ENCODING_HEADER);
    if (GZIP_ENCODING.equalsIgnoreCase(contentEncoding)) {
      log.debug("Response content encoding is {}, unzipping response body", contentEncoding);
      responseBodyStream = new GZIPInputStream(responseBodyStream);
    }
    return responseBodyStream;
  }
}

package datadog.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
  private static final String IDENTITY_ENCODING = "identity";

  private final String apiKey;
  private final String traceId;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final boolean responseCompression;
  private final HttpUrl hostUrl;
  private final OkHttpClient httpClient;
  private final Map<String, String> additionalHeaders;

  public IntakeApi(
      HttpUrl hostUrl,
      String apiKey,
      String traceId,
      HttpRetryPolicy.Factory retryPolicyFactory,
      OkHttpClient httpClient,
      boolean responseCompression) {
    this(
        hostUrl,
        apiKey,
        traceId,
        retryPolicyFactory,
        httpClient,
        responseCompression,
        Collections.emptyMap());
  }

  public IntakeApi(
      HttpUrl hostUrl,
      String apiKey,
      String traceId,
      HttpRetryPolicy.Factory retryPolicyFactory,
      OkHttpClient httpClient,
      boolean responseCompression,
      Map<String, String> additionalHeaders) {
    this.hostUrl = hostUrl;
    this.apiKey = apiKey;
    this.traceId = traceId;
    this.retryPolicyFactory = retryPolicyFactory;
    this.responseCompression = responseCompression;
    this.httpClient = httpClient;
    this.additionalHeaders =
        Collections.unmodifiableMap(new HashMap<String, String>(additionalHeaders));
  }

  @Override
  public <T> T post(
      String uri,
      RequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException {
    HttpUrl url = hostUrl.resolve(uri);
    Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader(DD_API_KEY_HEADER, apiKey)
            .addHeader(X_DATADOG_TRACE_ID_HEADER, traceId)
            .addHeader(X_DATADOG_PARENT_ID_HEADER, traceId);
    for (final Map.Entry<String, String> header : additionalHeaders.entrySet()) {
      requestBuilder.addHeader(header.getKey(), header.getValue());
    }

    if (requestListener != null) {
      requestBuilder.tag(OkHttpUtils.CustomListener.class, requestListener);
    }

    if (requestCompression) {
      requestBuilder.addHeader(CONTENT_ENCODING_HEADER, GZIP_ENCODING);
    }

    // OkHttp adds Accept-Encoding: gzip when this header is absent. Always set the header so a
    // caller can disable response compression on the wire.
    requestBuilder.addHeader(
        ACCEPT_ENCODING_HEADER, responseCompression ? GZIP_ENCODING : IDENTITY_ENCODING);

    Request request = requestBuilder.build();
    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
      if (response.isSuccessful()) {
        log.debug("Request to {} returned successful response: {}", uri, response.code());
        InputStream responseBodyStream = response.body().byteStream();

        String contentEncoding = response.header(CONTENT_ENCODING_HEADER);
        if (GZIP_ENCODING.equalsIgnoreCase(contentEncoding)) {
          log.debug("Response content encoding is {}, unzipping response body", contentEncoding);
          responseBodyStream = new GZIPInputStream(responseBodyStream);
        }

        return responseParser.apply(responseBodyStream);
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

package datadog.trace.civisibility.communication;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.civisibility.utils.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API for posting HTTP requests directly to backend, without the need for DD Agent */
public class IntakeApi implements BackendApi {

  private static final Logger log = LoggerFactory.getLogger(IntakeApi.class);

  public static final String API_VERSION = "v2";

  private static final String DD_API_KEY_HEADER = "dd-api-key";
  private static final String X_DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id";
  private static final String X_DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id";
  private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
  private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
  private static final String GZIP_ENCODING = "gzip";

  private final String apiKey;
  private final String traceId;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final boolean gzipEnabled;
  private final HttpUrl hostUrl;
  private final OkHttpClient httpClient;

  public IntakeApi(
      HttpUrl hostUrl,
      String apiKey,
      String traceId,
      long timeoutMillis,
      HttpRetryPolicy.Factory retryPolicyFactory) {
    this(hostUrl, apiKey, traceId, timeoutMillis, retryPolicyFactory, true);
  }

  public IntakeApi(
      HttpUrl hostUrl,
      String apiKey,
      String traceId,
      long timeoutMillis,
      HttpRetryPolicy.Factory retryPolicyFactory,
      boolean gzipEnabled) {
    this.hostUrl = hostUrl;
    this.apiKey = apiKey;
    this.traceId = traceId;
    this.retryPolicyFactory = retryPolicyFactory;
    this.gzipEnabled = gzipEnabled;

    httpClient = OkHttpUtils.buildHttpClient(hostUrl, timeoutMillis);
  }

  @Override
  public <T> T post(
      String uri, RequestBody requestBody, IOThrowingFunction<InputStream, T> responseParser)
      throws IOException {
    HttpUrl url = hostUrl.resolve(uri);
    Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader(DD_API_KEY_HEADER, apiKey)
            .addHeader(X_DATADOG_TRACE_ID_HEADER, traceId)
            .addHeader(X_DATADOG_PARENT_ID_HEADER, traceId);

    if (gzipEnabled) {
      requestBuilder.addHeader(ACCEPT_ENCODING_HEADER, GZIP_ENCODING);
    }

    Request request = requestBuilder.build();
    HttpRetryPolicy retryPolicy = retryPolicyFactory.create();
    try (okhttp3.Response response =
        OkHttpUtils.sendWithRetries(httpClient, retryPolicy, request)) {
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

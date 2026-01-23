package datadog.communication.http;

import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.http.client.HttpResponse;
import datadog.communication.http.client.HttpUrl;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import okhttp3.Dispatcher;
import okhttp3.EventListener;

/**
 * Deprecated wrapper for HttpUtils. All methods delegate to HttpUtils.
 * Use HttpUtils instead.
 *
 * @deprecated Renamed to HttpUtils to reflect generic HTTP client support. Use {@link HttpUtils} instead.
 */
@Deprecated
public final class OkHttpUtils {

  private OkHttpUtils() {
    // Utility class
  }

  // Delegate constants
  public static final String DATADOG_CONTAINER_ID = HttpUtils.DATADOG_CONTAINER_ID;
  public static final String DATADOG_CONTAINER_TAGS_HASH = HttpUtils.DATADOG_CONTAINER_TAGS_HASH;

  /**
   * @deprecated Use HttpListener in the abstract API instead
   */
  @Deprecated
  public abstract static class CustomListener extends EventListener {}

  // Delegate all buildHttpClient methods
  public static HttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return HttpUtils.buildHttpClient(url, timeoutMillis);
  }

  public static HttpClient buildHttpClient(
      final boolean isHttp,
      final String unixDomainSocketPath,
      final String namedPipe,
      final long timeoutMillis) {
    return HttpUtils.buildHttpClient(isHttp, unixDomainSocketPath, namedPipe, timeoutMillis);
  }

  public static HttpClient buildHttpClient(
      final Config config,
      final Dispatcher dispatcher,
      final HttpUrl url,
      final Boolean retryOnConnectionFailure,
      final Integer maxRunningRequests,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    return HttpUtils.buildHttpClient(
        config,
        dispatcher,
        url,
        retryOnConnectionFailure,
        maxRunningRequests,
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        timeoutMillis);
  }

  // Delegate prepareRequest methods
  public static HttpRequest.Builder prepareRequest(final HttpUrl url, Map<String, String> headers) {
    return HttpUtils.prepareRequest(url, headers);
  }

  public static HttpRequest.Builder prepareRequest(
      final HttpUrl url,
      final Map<String, String> headers,
      final Config config,
      final boolean agentless) {
    return HttpUtils.prepareRequest(url, headers, config, agentless);
  }

  // Delegate request body factory methods
  public static HttpRequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return HttpUtils.msgpackRequestBodyOf(buffers);
  }

  public static HttpRequestBody gzippedMsgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return HttpUtils.gzippedMsgpackRequestBodyOf(buffers);
  }

  public static HttpRequestBody gzippedRequestBodyOf(HttpRequestBody delegate) {
    return HttpUtils.gzippedRequestBodyOf(delegate);
  }

  public static HttpRequestBody jsonRequestBodyOf(byte[] json) {
    return HttpUtils.jsonRequestBodyOf(json);
  }

  // Delegate sendWithRetries
  public static HttpResponse sendWithRetries(
      HttpClient httpClient, HttpRetryPolicy.Factory retryPolicyFactory, HttpRequest request)
      throws IOException {
    return HttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request);
  }

  // Delegate isPlainHttp
  public static boolean isPlainHttp(final HttpUrl url) {
    return HttpUtils.isPlainHttp(url);
  }
}

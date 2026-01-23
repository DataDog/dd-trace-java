package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpResponse;

/**
 * Factory for creating HttpResponse instances. This factory selects the appropriate
 * implementation based on the response type.
 *
 * <p>For now, this uses OkHttp implementation. In Phase 2, Task 2.3, this will be enhanced
 * to support JDK HttpClient based on Java version detection and configuration.
 */
final class HttpResponseFactory {

  private HttpResponseFactory() {
    // Utility class
  }

  /**
   * Wraps an implementation-specific response object.
   *
   * @param response the response to wrap (currently okhttp3.Response)
   * @return wrapped HttpResponse, or null if input is null
   */
  static HttpResponse wrap(Object response) {
    if (response == null) {
      return null;
    }
    if (response instanceof okhttp3.Response) {
      return OkHttpResponse.wrap((okhttp3.Response) response);
    }
    throw new IllegalArgumentException("Unsupported response type: " + response.getClass());
  }
}

package datadog.http.client.jdk;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.http.client.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** JDK HttpClient-based implementation of HttpResponse. */
public final class JdkHttpResponse implements HttpResponse {
  private final java.net.http.HttpResponse<InputStream> delegate;

  private JdkHttpResponse(java.net.http.HttpResponse<InputStream> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps a java.net.http.HttpResponse.
   *
   * @param jdkResponse the JDK HttpResponse to wrap
   * @return wrapped HttpResponse
   */
  static HttpResponse wrap(java.net.http.HttpResponse<InputStream> jdkResponse) {
    if (jdkResponse == null) {
      return null;
    }
    return new JdkHttpResponse(jdkResponse);
  }

  /**
   * Unwraps to get the underlying java.net.http.HttpResponse.
   *
   * @return the underlying java.net.http.HttpResponse
   */
  java.net.http.HttpResponse<InputStream> unwrap() {
    return delegate;
  }

  @Override
  public int code() {
    return delegate.statusCode();
  }

  @Override
  public boolean isSuccessful() {
    int code = delegate.statusCode();
    return code >= 200 && code < 300;
  }

  @Override
  public String header(String name) {
    return delegate.headers().firstValue(name).orElse(null);
  }

  @Override
  public List<String> headers(String name) {
    return delegate.headers().allValues(name);
  }

  @Override
  public Set<String> headerNames() {
    // No internal copy and already immutable
    return delegate.headers().map().keySet();
  }

  @Override
  public InputStream body() {
    return delegate.body();
  }

  @Override
  public String bodyAsString() throws IOException {
    try (InputStream body = body()) {
      return new String(body.readAllBytes(), parseCharset());
    }
  }

  private Charset parseCharset() {
    return this.delegate
        .headers()
        .firstValue("Content-Type")
        .map(
            contentType -> {
              try {
                // Parse media type parameters
                int semicolon = contentType.indexOf(';');
                if (semicolon == -1) {
                  return UTF_8;
                }
                String params = contentType.substring(semicolon + 1);
                // Use fast path single character split with limit
                for (String param : params.split(";", 10)) {
                  String[] keyValue = param.split("=", 2);
                  if (keyValue.length == 2 && keyValue[0].trim().equalsIgnoreCase("charset")) {
                    String charset = keyValue[1].trim();
                    // Remove quotes if present
                    if (charset.startsWith("\"") && charset.endsWith("\"")) {
                      charset = charset.substring(1, charset.length() - 1);
                    }
                    return Charset.forName(charset);
                  }
                }
              } catch (Exception e) {
                return UTF_8;
              }
              return UTF_8;
            })
        .orElse(UTF_8);
  }

  @Override
  public void close() {
    // TODO Need review. Unclear if needed
    // JDK HttpResponse doesn't require explicit closing
    // The InputStream will be closed by the caller
    try {
      InputStream body = delegate.body();
      if (body != null) {
        body.close();
      }
    } catch (Exception e) {
      // Ignore exceptions during close
    }
  }
}

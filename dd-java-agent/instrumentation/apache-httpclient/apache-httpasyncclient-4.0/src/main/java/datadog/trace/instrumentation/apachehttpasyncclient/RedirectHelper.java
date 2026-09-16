package datadog.trace.instrumentation.apachehttpasyncclient;

import java.net.URI;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;

public final class RedirectHelper {
  private RedirectHelper() {}

  public static boolean isSameOrigin(
      final HttpContext context, final HttpRequest original, final HttpRequest redirect) {
    if (!(original instanceof HttpRequestWrapper) || !(redirect instanceof HttpUriRequest)) {
      return false;
    }
    HttpRequest unwrappedOriginal = ((HttpRequestWrapper) original).getOriginal();
    if (!(unwrappedOriginal instanceof HttpUriRequest)) {
      return false;
    }
    URI originalUri = ((HttpUriRequest) unwrappedOriginal).getURI();
    URI redirectUri = ((HttpUriRequest) redirect).getURI();
    if (originalUri == null || redirectUri == null) {
      return false;
    }

    originalUri = resolveOriginalUri(context, originalUri);
    if (!redirectUri.isAbsolute()) {
      redirectUri = originalUri.resolve(redirectUri);
    }
    if (originalUri.getScheme() == null || redirectUri.getScheme() == null) {
      return false;
    }

    String originalHost = originalUri.getHost();
    String redirectHost = redirectUri.getHost();
    if (originalHost == null || redirectHost == null) {
      return false;
    }

    return originalUri.getScheme().equalsIgnoreCase(redirectUri.getScheme())
        && originalHost.equalsIgnoreCase(redirectHost)
        && effectivePort(originalUri) == effectivePort(redirectUri);
  }

  private static URI resolveOriginalUri(final HttpContext context, final URI originalUri) {
    if (originalUri.isAbsolute()) {
      return originalUri;
    }
    Object targetHost = context.getAttribute("http.target_host");
    if (!(targetHost instanceof HttpHost)) {
      return originalUri;
    }
    HttpHost host = (HttpHost) targetHost;
    try {
      return URI.create(host.toURI()).resolve(originalUri);
    } catch (IllegalArgumentException e) {
      return originalUri;
    }
  }

  private static int effectivePort(final URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    String scheme = uri.getScheme();
    if ("http".equalsIgnoreCase(scheme)) {
      return 80;
    }
    if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    }
    return -1;
  }
}

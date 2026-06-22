package datadog.trace.core.tagprocessor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.bootstrap.instrumentation.api.OtelHttpMethods;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.net.URI;

/**
 * Renames HTTP server and client span attributes from the Datadog names to the OpenTelemetry HTTP
 * semantic-convention names when {@code DD_TRACE_OTEL_SEMANTICS_ENABLED=true}. See <a
 * href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">the spec</a>.
 *
 * <p>This runs in the <em>lazy</em> post-processor chain, i.e. at serialization time, which is
 * <em>after</em> {@code QueryObfuscator} (so the query string is already obfuscated), after the
 * eager {@code PeerServiceCalculator} (so {@code peer.service} is already derived from {@code
 * peer.hostname}), and — crucially — after {@code metricsAggregator.publish(...)} (see {@code
 * CoreTracer.write}), so trace stats have already read the Datadog tag names and the dedicated
 * status-code field. Renaming here therefore keeps obfuscation, {@code peer.service}, AppSec {@code
 * actor.ip}, and trace stats intact while emitting only the OpenTelemetry names on the wire — the
 * same "keep Datadog internals, rename late" approach dd-trace-js takes in its span processor.
 *
 * <p>The dedicated {@code http.status_code} serialization is suppressed separately in {@link
 * DDSpanContext} (the status lives in a field, not the tag map); this processor emits {@code
 * http.response.status_code} from that field.
 */
public final class HttpOtelSemanticsTagsPostProcessor extends TagsPostProcessor {

  @Override
  public void processTags(
      final TagMap tags, final DDSpanContext spanContext, final AppendableSpanLinks spanLinks) {
    final CharSequence spanType = spanContext.getSpanType();
    if (spanType == null) {
      return;
    }
    final String type = spanType.toString();
    final boolean client = DDSpanTypes.HTTP_CLIENT.equals(type);
    final boolean server = DDSpanTypes.HTTP_SERVER.equals(type);
    if (!client && !server) {
      return;
    }
    // Idempotency guard: the rename has already run for this span.
    if (tags.getObject(Tags.HTTP_REQUEST_METHOD) != null) {
      return;
    }

    // --- request method (http.method -> http.request.method, normalizing unknown verbs) ---
    final Object methodObj = tags.getObject(Tags.HTTP_METHOD);
    final String method = methodObj == null ? null : methodObj.toString();
    setRequestMethod(tags, method);
    tags.remove(Tags.HTTP_METHOD);

    // --- user agent (server) ---
    final Object userAgent = tags.getObject(Tags.HTTP_USER_AGENT);
    if (userAgent != null) {
      tags.set(Tags.USER_AGENT_ORIGINAL, userAgent.toString());
      tags.remove(Tags.HTTP_USER_AGENT);
    }

    // --- status code: read the dedicated field (left intact for trace stats); the legacy
    // http.status_code serialization is suppressed in DDSpanContext under the flag. ---
    final short status = spanContext.getHttpStatusCode();
    if (status != 0) {
      tags.set(Tags.HTTP_RESPONSE_STATUS_CODE, Integer.toString(status));
      // OTel marks an error on server 5xx and client 4xx/5xx; record error.type unless an
      // exception already set it (the spec prefers the exception type).
      final boolean error = server ? status >= 500 : status >= 400;
      if (error && tags.getObject(DDTags.ERROR_TYPE) == null) {
        tags.set(DDTags.ERROR_TYPE, Integer.toString(status));
      }
    }

    if (server) {
      processServer(tags);
    } else {
      processClient(tags);
    }
    // The span name ("{method}", or "HTTP" for unknown methods) is set in the HTTP decorators at
    // request time: the resource is serialized before this lazy post-processor runs.
  }

  private void processServer(final TagMap tags) {
    // server.address from http.hostname
    final Object host = tags.getObject(Tags.HTTP_HOSTNAME);
    if (host != null) {
      tags.set(Tags.SERVER_ADDRESS, host.toString());
      tags.remove(Tags.HTTP_HOSTNAME);
    }
    // url.scheme / url.path / server.port decomposed from http.url (already obfuscated)
    final Object url = tags.getObject(Tags.HTTP_URL);
    if (url != null) {
      decomposeServerUrl(tags, url.toString());
      tags.remove(Tags.HTTP_URL);
    }
    // url.query from the already-obfuscated http.query (obfuscation preserved)
    final Object query = tags.getObject(DDTags.HTTP_QUERY);
    if (query != null) {
      final String q = query.toString();
      if (!q.isEmpty()) {
        tags.set(Tags.URL_QUERY, q);
      }
    }
    tags.remove(DDTags.HTTP_QUERY);
    tags.remove(DDTags.HTTP_FRAGMENT);
    // client.address from http.client_ip (actor.ip was already reflected by AppSec at request time)
    final Object clientIp = tags.getObject(Tags.HTTP_CLIENT_IP);
    if (clientIp != null) {
      tags.set(Tags.CLIENT_ADDRESS, clientIp.toString());
      tags.remove(Tags.HTTP_CLIENT_IP);
    }
    // network.peer.address from network.client.ip
    final Object peerIp = tags.getObject(Tags.NETWORK_CLIENT_IP);
    if (peerIp != null) {
      tags.set(Tags.NETWORK_PEER_ADDRESS, peerIp.toString());
      tags.remove(Tags.NETWORK_CLIENT_IP);
    }
  }

  private void processClient(final TagMap tags) {
    // url.full from http.url (which QueryObfuscator already appended the obfuscated query to),
    // with embedded credentials redacted.
    final Object url = tags.getObject(Tags.HTTP_URL);
    String urlFull = null;
    if (url != null) {
      urlFull = redactCredentials(url.toString());
      tags.set(Tags.URL_FULL, urlFull);
      tags.remove(Tags.HTTP_URL);
    }
    // server.address from peer.hostname (peer.service was already derived from it)
    final Object host = tags.getObject(Tags.PEER_HOSTNAME);
    if (host != null) {
      tags.set(Tags.SERVER_ADDRESS, host.toString());
      tags.remove(Tags.PEER_HOSTNAME);
    }
    // server.port from peer.port, falling back to the scheme default (80/443) since the spec marks
    // it required for client spans.
    final Object peerPort = tags.getObject(Tags.PEER_PORT);
    int port = peerPort instanceof Number ? ((Number) peerPort).intValue() : -1;
    if (port <= 0 && urlFull != null) {
      port = defaultPortForScheme(urlFull);
    }
    if (port > 0) {
      tags.set(Tags.SERVER_PORT, port);
    }
    tags.remove(Tags.PEER_PORT);
    // query/fragment are folded into url.full; drop the separate Datadog tags
    tags.remove(DDTags.HTTP_QUERY);
    tags.remove(DDTags.HTTP_FRAGMENT);
  }

  private static void setRequestMethod(final TagMap tags, final String method) {
    if (method == null) {
      tags.set(Tags.HTTP_REQUEST_METHOD, OtelHttpMethods.OTHER_METHOD);
    } else if (OtelHttpMethods.isKnown(method)) {
      tags.set(Tags.HTTP_REQUEST_METHOD, method);
    } else {
      tags.set(Tags.HTTP_REQUEST_METHOD, OtelHttpMethods.OTHER_METHOD);
      tags.set(Tags.HTTP_REQUEST_METHOD_ORIGINAL, method);
    }
  }

  private static void decomposeServerUrl(final TagMap tags, final String url) {
    // Strip the query/fragment before parsing: QueryObfuscator appends the (already obfuscated)
    // query to http.url, and the "<redacted>" placeholder contains characters that are illegal in
    // java.net.URI. url.query is taken separately from the http.query tag.
    String base = url;
    final int query = base.indexOf('?');
    if (query >= 0) {
      base = base.substring(0, query);
    }
    final int fragment = base.indexOf('#');
    if (fragment >= 0) {
      base = base.substring(0, fragment);
    }
    try {
      final URI uri = URI.create(base);
      final String scheme = uri.getScheme();
      if (scheme != null) {
        tags.set(Tags.URL_SCHEME, scheme);
      }
      final String path = uri.getRawPath();
      if (path != null && !path.isEmpty()) {
        tags.set(Tags.URL_PATH, path);
      }
      final int port = uri.getPort();
      if (port > 0) {
        tags.set(Tags.SERVER_PORT, port);
      }
    } catch (final IllegalArgumentException e) {
      // Unparseable URL: fall back to exposing the path-ish portion so it isn't lost.
      tags.set(Tags.URL_PATH, base);
    }
  }

  /** Redacts only the authority's userinfo credentials (first occurrence) from an absolute URL. */
  private static String redactCredentials(final String url) {
    final int at = url.indexOf('@');
    if (at < 0) {
      return url;
    }
    final int schemeEnd = url.indexOf("://");
    if (schemeEnd < 0 || at < schemeEnd) {
      return url;
    }
    final String userInfo = url.substring(schemeEnd + 3, at);
    if (userInfo.isEmpty() || userInfo.indexOf('/') >= 0) {
      // '@' was in the path/query, not the authority
      return url;
    }
    final String redacted = userInfo.indexOf(':') >= 0 ? "REDACTED:REDACTED" : "REDACTED";
    return url.substring(0, schemeEnd + 3) + redacted + url.substring(at);
  }

  private static int defaultPortForScheme(final String url) {
    if (url.startsWith("https")) {
      return 443;
    }
    if (url.startsWith("http")) {
      return 80;
    }
    return -1;
  }
}

package datadog.trace.instrumentation.ratpack;

import com.google.common.net.HostAndPort;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;
import ratpack.http.Request;

final class RequestURIAdapterAdapter extends URIDataAdapterBase {

  // the cache size can be small, as this is a cache for a string representation
  // of the local address of the socket. Usually there will be only one element
  private static final DDCache<String, String> DOMAIN_NAME_MAPPING = DDCaches.newFixedSizeCache(4);

  private static final Function<String, String> GET_CANONICAL_NAME =
      ip -> {
        try {
          return InetAddress.getByName(ip).getCanonicalHostName();
        } catch (UnknownHostException e) {
          return ip;
        }
      };

  private final Request request;
  private final HostAndPort hostAndPort;

  RequestURIAdapterAdapter(Request request) {
    this.request = request;
    this.hostAndPort = request.getLocalAddress();
  }

  @Override
  public String scheme() {
    return hostAndPort.getPort() == 443 ? "https" : "http";
  }

  @Override
  public String host() {
    // this is a local address, so not too worried about this blowing up
    return DOMAIN_NAME_MAPPING.computeIfAbsent(hostAndPort.getHost(), GET_CANONICAL_NAME);
  }

  @Override
  public int port() {
    return hostAndPort.getPort();
  }

  @Override
  public String path() {
    return request.getPath();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  public String query() {
    return request.getQuery();
  }

  @Override
  public boolean supportsRaw() {
    return false;
  }

  @Override
  public String rawPath() {
    return null;
  }

  @Override
  public String rawQuery() {
    return null;
  }
}

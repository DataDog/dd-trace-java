package datadog.trace.instrumentation.ratpack;

import com.google.common.net.HostAndPort;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import ratpack.http.Request;

final class RequestURIAdapterAdapter extends URIDataAdapterBase {

  private final ConcurrentHashMap<String, String> DOMAIN_NAME_MAPPING = new ConcurrentHashMap<>();

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
    return DOMAIN_NAME_MAPPING.computeIfAbsent(
        hostAndPort.getHost(),
        ip -> {
          try {
            return InetAddress.getByName(ip).getCanonicalHostName();
          } catch (UnknownHostException e) {
            return ip;
          }
        });
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

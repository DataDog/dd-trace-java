package datadog.trace.util;

import datadog.trace.api.Config;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class AgentProxySelector extends ProxySelector {
  public static final ProxySelector INSTANCE = new AgentProxySelector();

  private static final List<Proxy> DIRECT = Collections.singletonList(Proxy.NO_PROXY);

  private final Set<String> noProxyHosts = Config.get().getNoProxyHosts();

  private final ProxySelector defaultProxySelector = ProxySelector.getDefault();

  @Override
  public List<Proxy> select(final URI uri) {
    if (null != uri.getHost() && noProxyHosts.contains(uri.getHost())) {
      return DIRECT;
    } else {
      return defaultProxySelector.select(uri);
    }
  }

  @Override
  public void connectFailed(final URI uri, final SocketAddress sa, final IOException ioe) {
    defaultProxySelector.connectFailed(uri, sa, ioe);
  }
}

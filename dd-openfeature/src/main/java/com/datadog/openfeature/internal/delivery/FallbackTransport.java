package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.connector.EventProxyUnavailableException;
import com.datadog.openfeature.internal.connector.EventTransport;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts through the event proxy, switching to direct intake for good once the proxy is definitively
 * unavailable. Ambiguous proxy failures are not replayed through direct intake.
 */
public final class FallbackTransport implements EventTransport {
  private static final Logger LOGGER = LoggerFactory.getLogger(FallbackTransport.class);

  private final EventTransport proxy;
  private final EventTransport direct;
  private volatile boolean proxyUnavailable;

  public FallbackTransport(final EventTransport proxy, final EventTransport direct) {
    this.proxy = proxy;
    this.direct = direct;
  }

  @Override
  public void post(final String route, final byte[] json) throws IOException {
    if (!this.proxyUnavailable) {
      try {
        this.proxy.post(route, json);
        return;
      } catch (final EventProxyUnavailableException e) {
        LOGGER.debug(
            "Switching Feature Flags event delivery from the event proxy to direct intake");
        this.proxyUnavailable = true;
      }
    }
    this.direct.post(route, json);
  }
}

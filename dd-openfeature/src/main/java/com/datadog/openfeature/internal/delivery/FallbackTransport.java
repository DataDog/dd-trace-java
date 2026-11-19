package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.bridge.v1.EventProxyUnavailableException;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts through the event proxy, switching to direct intake for good on the first proxy failure.
 * Only a definitively rejected batch is replayed through direct intake, an ambiguously failed batch
 * is not to avoid sending it twice.
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
        switchToDirectIntake();
      } catch (final IOException e) {
        switchToDirectIntake();
        throw e;
      }
    }
    this.direct.post(route, json);
  }

  private void switchToDirectIntake() {
    LOGGER.debug("Switching Feature Flags event delivery from the event proxy to direct intake");
    this.proxyUnavailable = true;
  }
}

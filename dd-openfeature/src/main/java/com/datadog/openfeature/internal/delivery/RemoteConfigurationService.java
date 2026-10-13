package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.ConfigurationService;
import com.datadog.openfeature.internal.connector.ConfigurationSource;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receives the flag configuration from Remote Configuration, through the Datadog Java agent. */
public final class RemoteConfigurationService implements ConfigurationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteConfigurationService.class);

  private final ConfigurationSource source;
  private AutoCloseable subscription;

  public RemoteConfigurationService(final ConfigurationSource source) {
    this.source = source;
  }

  @Override
  public synchronized void start(final Consumer<ServerConfiguration> listener) {
    if (this.subscription != null) {
      return;
    }
    this.subscription =
        this.source.subscribe(
            content -> {
              if (content == null) {
                listener.accept(null);
                return;
              }
              try {
                final ServerConfiguration configuration = UniversalFlagConfigParser.parse(content);
                if (configuration != null) {
                  listener.accept(configuration);
                }
              } catch (final IOException | RuntimeException e) {
                LOGGER.debug("Remote Configuration returned a malformed flag configuration", e);
              }
            });
  }

  @Override
  public synchronized void close() {
    if (this.subscription != null) {
      try {
        this.subscription.close();
      } catch (final Exception e) {
        LOGGER.debug("Failed to unsubscribe from Remote Configuration", e);
      }
      this.subscription = null;
    }
  }
}

package com.datadog.featureflag;

import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards the raw Feature Flags Remote Configuration documents to the SDK subscribers. The SDK
 * owns the document format, so the agent does not parse it.
 */
public class RemoteConfigServiceImpl implements ConfigurationChangesTypedListener<byte[]> {
  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteConfigServiceImpl.class);

  private final ConfigurationPoller configurationPoller;
  private final List<Consumer<byte[]>> subscribers = new CopyOnWriteArrayList<>();
  private volatile byte[] current;
  private boolean started;

  public RemoteConfigServiceImpl(final ConfigurationPoller configurationPoller) {
    this.configurationPoller = configurationPoller;
  }

  /**
   * Registers the Feature Flags product. Registering at agent startup lets the first Remote
   * Configuration poll ask for it.
   */
  public synchronized void start() {
    if (this.started) {
      return;
    }
    this.started = true;
    this.configurationPoller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    this.configurationPoller.addListener(Product.FFE_FLAGS, content -> content, this);
    this.configurationPoller.start();
  }

  /**
   * Subscribes to the configuration documents.
   *
   * @param subscriber the subscriber, receiving the current document if any.
   * @return the handle to close to unsubscribe.
   */
  public AutoCloseable subscribe(final Consumer<byte[]> subscriber) {
    start();
    this.subscribers.add(subscriber);
    final byte[] document = this.current;
    if (document != null) {
      subscriber.accept(document);
    }
    return () -> this.subscribers.remove(subscriber);
  }

  /** Unregisters the Feature Flags product and drops the subscribers. */
  public synchronized void close() {
    if (!this.started) {
      return;
    }
    this.started = false;
    this.configurationPoller.removeCapabilities(
        Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    this.configurationPoller.removeListeners(Product.FFE_FLAGS);
    this.subscribers.clear();
    this.current = null;
  }

  @Override
  public void accept(
      final String configKey,
      @Nullable final byte[] content,
      final PollingRateHinter pollingRateHinter) {
    this.current = content;
    for (final Consumer<byte[]> subscriber : this.subscribers) {
      try {
        subscriber.accept(content);
      } catch (final RuntimeException e) {
        LOGGER.debug("Feature Flags configuration subscriber failed", e);
      }
    }
  }
}

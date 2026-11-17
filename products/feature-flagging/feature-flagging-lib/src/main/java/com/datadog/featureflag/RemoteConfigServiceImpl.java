package com.datadog.featureflag;

import static datadog.remoteconfig.Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1;
import static datadog.remoteconfig.Product.FFE_FLAGS;
import static java.util.Collections.emptySet;

import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
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
  private final ReentrantLock capabilityUpdateLock = new ReentrantLock();
  private final ReentrantLock lifecycleUpdateLock = new ReentrantLock();
  private volatile byte[] current;

  /**
   * Whether the current document was received before any subscriber, so the poller acknowledged it
   * without validation. Guarded by the subscribers lock.
   */
  private boolean unvalidated;

  /** Desired and applied lifecycle state, guarded by the subscribers lock. */
  private boolean started;

  private boolean lifecycleStarted;
  private long refreshGeneration;
  private long appliedRefreshGeneration;
  private boolean deliveryStopped;
  private final Map<Consumer<byte[]>, Boolean> dependentFlagSubscribers = new IdentityHashMap<>();
  private boolean dependentFlagsAdvertised;

  /** Guarded by the subscribers lock. */
  private boolean deliverySuspended;

  public RemoteConfigServiceImpl(final ConfigurationPoller configurationPoller) {
    this.configurationPoller = configurationPoller;
  }

  /**
   * Registers the Feature Flags product. Registering at agent startup lets the first Remote
   * Configuration poll ask for it.
   */
  public void start() {
    synchronized (this.subscribers) {
      if (!this.started) {
        this.started = true;
      }
    }
    updateLifecycle();
  }

  private void listen() {
    // Listen as serialized event to prevent depending on the deserializer from agent
    this.configurationPoller.addListener(FFE_FLAGS, content -> content, this);
  }

  /**
   * Subscribes to the configuration documents.
   *
   * @param subscriber the subscriber, receiving the current document if any.
   * @return the handle to close to unsubscribe.
   */
  public AutoCloseable subscribe(final Consumer<byte[]> subscriber) {
    return subscribe(subscriber, emptySet());
  }

  /** Subscribes with the evaluator capabilities reported by this SDK instance. */
  public AutoCloseable subscribe(
      final Consumer<byte[]> subscriber, final Set<String> capabilities) {
    start();
    // A distinct forwarding listener keeps repeated registrations independently removable.
    final Consumer<byte[]> registered = subscriber::accept;
    boolean requestAgain = false;
    // Keep registration and replay atomic with notification snapshots.
    synchronized (this.subscribers) {
      this.subscribers.add(registered);
      this.dependentFlagSubscribers.put(
          registered, capabilities.contains("dependent-flag-variant-key-v1"));
      if (this.dependentFlagsAdvertised
          && !capabilities.contains("dependent-flag-variant-key-v1")) {
        this.deliverySuspended = true;
        this.current = null;
      }
      final byte[] document = this.current;
      if (document != null) {
        try {
          subscriber.accept(document);
        } catch (final RuntimeException e) {
          // A rejection of a document received with subscribers is already reported.
          requestAgain = this.unvalidated;
          LOGGER.debug("Feature Flags configuration subscriber rejected the current document", e);
        }
        this.unvalidated = false;
      }
    }
    try {
      updateDependentFlagCapability();
    } catch (final RuntimeException | Error failure) {
      synchronized (this.subscribers) {
        this.subscribers.remove(registered);
        this.dependentFlagSubscribers.remove(registered);
        this.current = null;
      }
      try {
        updateDependentFlagCapability();
      } catch (final RuntimeException | Error rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    }
    if (requestAgain) {
      requestAgain();
    }
    return () -> {
      final Boolean supported;
      synchronized (this.subscribers) {
        this.subscribers.remove(registered);
        supported = this.dependentFlagSubscribers.remove(registered);
      }
      if (supported == null) {
        return;
      }
      try {
        updateDependentFlagCapability();
      } catch (final RuntimeException | Error failure) {
        synchronized (this.subscribers) {
          this.subscribers.add(registered);
          this.dependentFlagSubscribers.put(registered, supported);
          this.current = null;
        }
        try {
          updateDependentFlagCapability();
        } catch (final RuntimeException | Error rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    };
  }

  private void updateDependentFlagCapability() {
    if (!this.capabilityUpdateLock.tryLock()) {
      return;
    }
    boolean locked = true;
    try {
      while (true) {
        final boolean supported;
        synchronized (this.subscribers) {
          supported =
              this.started
                  && !this.dependentFlagSubscribers.isEmpty()
                  && !this.dependentFlagSubscribers.containsValue(false);
          if (supported == this.dependentFlagsAdvertised) {
            this.deliverySuspended = false;
            // Unlock before releasing subscribers. A concurrent registration can only proceed
            // after the updater is available, so it cannot lose its transition request.
            this.capabilityUpdateLock.unlock();
            locked = false;
            return;
          }
          this.deliverySuspended = true;
          this.current = null;
        }
        // The poller invokes callbacks under its lock; never acquire it with subscribers locked.
        if (supported) {
          this.configurationPoller.addCapabilities(
              CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
        } else {
          this.configurationPoller.removeCapabilities(
              CAPABILITY_FFE_FLAG_DEPENDENT_FLAG_VARIANT_KEY_V1);
        }
        synchronized (this.subscribers) {
          this.dependentFlagsAdvertised = supported;
        }
        requestAgain();
      }
    } finally {
      if (locked) {
        this.capabilityUpdateLock.unlock();
      }
    }
  }

  /**
   * Makes the poller deliver the current document again, so it reports the rejection of a document
   * acknowledged before any subscriber validated it. Registering the product again drops its cached
   * documents. Runs outside the subscribers lock, as the poller notifies under its own lock.
   */
  private void requestAgain() {
    synchronized (this.subscribers) {
      if (!this.started) {
        return;
      }
      this.refreshGeneration++;
    }
    updateLifecycle();
  }

  private void updateLifecycle() {
    if (!this.lifecycleUpdateLock.tryLock()) {
      return;
    }
    boolean locked = true;
    try {
      while (true) {
        final boolean targetStarted;
        final boolean appliedStarted;
        final long refreshVersion;
        final boolean refresh;
        synchronized (this.subscribers) {
          targetStarted = this.started;
          appliedStarted = this.lifecycleStarted;
          refreshVersion = this.refreshGeneration;
          refresh =
              targetStarted
                  && targetStarted == appliedStarted
                  && refreshVersion != this.appliedRefreshGeneration;
          if (targetStarted == appliedStarted && !refresh) {
            this.lifecycleUpdateLock.unlock();
            locked = false;
            return;
          }
        }

        if (targetStarted != appliedStarted) {
          if (targetStarted) {
            LOGGER.debug("Feature Flagging starting remote_config configuration source");
            this.configurationPoller.addCapabilities(CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
            listen();
            this.configurationPoller.start();
          } else {
            LOGGER.debug("Feature Flagging remote_config configuration source stopped");
            this.configurationPoller.removeCapabilities(CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
            this.configurationPoller.removeListeners(FFE_FLAGS);
            this.configurationPoller.stop();
          }
          synchronized (this.subscribers) {
            this.lifecycleStarted = targetStarted;
            this.deliveryStopped = !targetStarted;
            if (this.deliveryStopped) {
              this.current = null;
            }
          }
        } else {
          LOGGER.debug("Requesting the rejected Feature Flags configuration again to report it");
          this.configurationPoller.removeListeners(FFE_FLAGS);
          listen();
          synchronized (this.subscribers) {
            this.appliedRefreshGeneration = refreshVersion;
          }
        }
      }
    } finally {
      if (locked) {
        this.lifecycleUpdateLock.unlock();
      }
    }
  }

  /**
   * Unregisters the Feature Flags product, releases the poller started by {@link #start()} and
   * drops the subscribers.
   */
  public void close() {
    final boolean wasStarted;
    synchronized (this.subscribers) {
      wasStarted = this.started;
      this.started = false;
      this.appliedRefreshGeneration = this.refreshGeneration;
      this.deliveryStopped = true;
      if (wasStarted) {
        this.subscribers.clear();
        this.dependentFlagSubscribers.clear();
        this.current = null;
      }
    }
    updateDependentFlagCapability();
    updateLifecycle();
  }

  /**
   * Forwards a configuration document to every subscriber.
   *
   * @param configKey the Remote Configuration key.
   * @param content the raw document, or {@code null} when the configuration is removed.
   * @param pollingRateHinter the polling rate hinter.
   * @throws RuntimeException the first subscriber failure, once every subscriber is notified, so
   *     the poller reports the document as not applied.
   */
  @Override
  public void accept(
      final String configKey,
      @Nullable final byte[] content,
      final PollingRateHinter pollingRateHinter) {
    final Iterator<Consumer<byte[]>> snapshot;
    synchronized (this.subscribers) {
      if (this.deliveryStopped) {
        return;
      }
      if (this.deliverySuspended) {
        throw new IllegalStateException(
            "Feature Flags configuration changed while evaluator capabilities were updating");
      }
      this.current = content;
      this.unvalidated = content != null && this.subscribers.isEmpty();
      snapshot = this.subscribers.iterator();
    }
    RuntimeException failure = null;
    while (snapshot.hasNext()) {
      final Consumer<byte[]> subscriber = snapshot.next();
      try {
        subscriber.accept(content);
      } catch (final RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    // Logged last: a diagnostic must not get in the way of the delivery.
    if (content == null) {
      LOGGER.debug("Feature Flagging remote_config configuration removed: key={}", configKey);
    } else {
      LOGGER.debug(
          "Feature Flagging remote_config configuration received: key={}, bytes={}",
          configKey,
          content.length);
    }
    if (failure != null) {
      throw failure;
    }
  }
}

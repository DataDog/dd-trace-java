package datadog.trace.api.gateway;

import static datadog.trace.api.gateway.Events.MAX_EVENTS;

import java.util.concurrent.atomic.AtomicReferenceArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstrumentationGateway implements CallbackProvider, SubscriptionService {
  private static final Logger log = LoggerFactory.getLogger(InstrumentationGateway.class);

  private final AtomicReferenceArray<Object> callbacks;

  public InstrumentationGateway() {
    callbacks = new AtomicReferenceArray<>(MAX_EVENTS);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <C> C getCallback(EventType<C> eventType) {
    return (C) callbacks.get(eventType.getId());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <C extends EventCallback> Subscription registerCallback(final EventType<C> eventType, final C callback) {
    if (!callbacks.compareAndSet(eventType.getId(), null, callback)) {
      C existing = (C) callbacks.get(eventType.getId());
      String message =
          "Trying to overwrite existing callback " + existing + " for event type " + eventType;
      log.warn(message);
      throw new IllegalStateException(message);
    }

    return new Subscription() {
      @Override
      public void cancel() {
        if (!callbacks.compareAndSet(eventType.getId(), callback, null)) {
          if (log.isDebugEnabled()) {
            log.debug("Failed to unregister callback {} for event type {}", callback, eventType);
          }
        }
      }
    };
  }
}

package datadog.trace.api.gateway;

import static datadog.trace.api.gateway.Events.MAX_EVENTS;
import static datadog.trace.api.gateway.Events.REQUEST_CLIENT_IP_ID;
import static datadog.trace.api.gateway.Events.REQUEST_ENDED_ID;
import static datadog.trace.api.gateway.Events.REQUEST_HEADER_DONE_ID;
import static datadog.trace.api.gateway.Events.REQUEST_HEADER_ID;
import static datadog.trace.api.gateway.Events.REQUEST_STARTED_ID;
import static datadog.trace.api.gateway.Events.REQUEST_URI_RAW_ID;

import datadog.trace.api.Function;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The implementation of the {@code CallbackProvider} and {@code SubscriptionService}. Only supports
 * one callback of each type right now.
 */
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
  public <C> Subscription registerCallback(final EventType<C> eventType, final C callback) {
    final C wrapped = wrap(eventType, callback);
    final int id = eventType.getId();
    if (!callbacks.compareAndSet(id, null, wrapped)) {
      C existing = (C) callbacks.get(id);
      String message =
          "Trying to overwrite existing callback " + existing + " for event type " + eventType;
      log.warn(message);
      throw new IllegalStateException(message);
    }

    return new Subscription() {
      @Override
      public void cancel() {
        if (!callbacks.compareAndSet(id, wrapped, null)) {
          if (log.isDebugEnabled()) {
            log.debug("Failed to unregister callback {} for event type {}", callback, eventType);
          }
        }
      }
    };
  }

  /** Ensure that callbacks don't leak exceptions */
  @SuppressWarnings("unchecked")
  public static <C> C wrap(final EventType<C> eventType, final C callback) {
    switch (eventType.getId()) {
      case REQUEST_STARTED_ID:
        return (C)
            new Supplier<Flow<RequestContext>>() {
              @Override
              public Flow<RequestContext> get() {
                try {
                  return ((Supplier<Flow<RequestContext>>) callback).get();
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
              // Make testing easier by delegating equals
              @Override
              public boolean equals(Object obj) {
                return callback.equals(obj);
              }
            };
      case REQUEST_ENDED_ID:
      case REQUEST_HEADER_DONE_ID:
        return (C)
            new Function<RequestContext, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx) {
                try {
                  return ((Function<RequestContext, Flow<Void>>) callback).apply(ctx);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
              // Make testing easier by delegating equals
              @Override
              public boolean equals(Object obj) {
                return callback.equals(obj);
              }
            };
      case REQUEST_HEADER_ID:
        return (C)
            new TriConsumer<RequestContext, String, String>() {
              @Override
              public void accept(RequestContext ctx, String key, String value) {
                try {
                  ((TriConsumer<RequestContext, String, String>) callback).accept(ctx, key, value);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                }
              }
              // Make testing easier by delegating equals
              @Override
              public boolean equals(Object obj) {
                return callback.equals(obj);
              }
            };
      case REQUEST_URI_RAW_ID:
        return (C)
            new BiFunction<RequestContext, URIDataAdapter, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, URIDataAdapter adapter) {
                try {
                  return ((BiFunction<RequestContext, URIDataAdapter, Flow<Void>>) callback)
                      .apply(ctx, adapter);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
              // Make testing easier by delegating equals
              @Override
              public boolean equals(Object obj) {
                return callback.equals(obj);
              }
            };
      case REQUEST_CLIENT_IP_ID:
        return (C)
            new BiFunction<RequestContext, String, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, String ip) {
                try {
                  return ((BiFunction<RequestContext, String, Flow<Void>>) callback).apply(ctx, ip);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
              // Make testing easier by delegating equals
              @Override
              public boolean equals(Object obj) {
                return callback.equals(obj);
              }
            };
      default:
        log.warn("Unwrapped callback for {}", eventType);
        return callback;
    }
  }
}

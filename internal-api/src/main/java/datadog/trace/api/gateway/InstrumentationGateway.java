package datadog.trace.api.gateway;

import static datadog.trace.api.gateway.Events.MAX_EVENTS;
import static datadog.trace.api.gateway.Events.REQUEST_BODY_DONE_ID;
import static datadog.trace.api.gateway.Events.REQUEST_BODY_START_ID;
import static datadog.trace.api.gateway.Events.REQUEST_CLIENT_SOCKET_ADDRESS_ID;
import static datadog.trace.api.gateway.Events.REQUEST_ENDED_ID;
import static datadog.trace.api.gateway.Events.REQUEST_HEADER_DONE_ID;
import static datadog.trace.api.gateway.Events.REQUEST_HEADER_ID;
import static datadog.trace.api.gateway.Events.REQUEST_METHOD_ID;
import static datadog.trace.api.gateway.Events.REQUEST_STARTED_ID;
import static datadog.trace.api.gateway.Events.REQUEST_URI_RAW_ID;

import datadog.trace.api.Function;
import datadog.trace.api.function.BiConsumer;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

  // for tests
  void reset() {
    for (int i = 0; i < callbacks.length(); i++) {
      callbacks.set(i, null);
    }
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
        return (C)
            new BiFunction<RequestContext, AgentSpan, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, AgentSpan agentSpan) {
                try {
                  return ((BiFunction<RequestContext, AgentSpan, Flow<Void>>) callback)
                      .apply(ctx, agentSpan);
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
      case REQUEST_METHOD_ID:
        return (C)
            new BiConsumer<RequestContext, String>() {
              @Override
              public void accept(RequestContext ctx, String method) {
                try {
                  ((BiConsumer<RequestContext, String>) callback).accept(ctx, method);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                }
              }

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
      case REQUEST_CLIENT_SOCKET_ADDRESS_ID:
        return (C)
            new TriFunction<RequestContext, String, Short, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, String ip, Short port) {
                try {
                  return ((TriFunction<RequestContext, String, Short, Flow<Void>>) callback)
                      .apply(ctx, ip, port);
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
      case REQUEST_BODY_START_ID:
        return (C)
            new BiFunction<RequestContext, StoredBodySupplier, Void>() {
              @Override
              public Void apply(RequestContext ctx, StoredBodySupplier storedBodySupplier) {
                try {
                  return ((BiFunction<RequestContext, StoredBodySupplier, Void>) callback)
                      .apply(ctx, storedBodySupplier);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return null;
                }
              }
            };
      case REQUEST_BODY_DONE_ID:
        return (C)
            new BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, StoredBodySupplier storedBodySupplier) {
                try {
                  return ((BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>) callback)
                      .apply(ctx, storedBodySupplier);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
            };
      default:
        log.warn("Unwrapped callback for {}", eventType);
        return callback;
    }
  }
}

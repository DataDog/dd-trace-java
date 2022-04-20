package datadog.trace.api.gateway;

import static datadog.trace.api.gateway.Events.*;

import datadog.trace.api.function.*;
import datadog.trace.api.function.Function;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.Map;
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
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <C> C wrap(final EventType<C> eventType, final C callback) {
    switch (eventType.getId()) {
      case REQUEST_STARTED_ID:
        return (C)
            new Supplier<Flow<Object>>() {
              @Override
              public Flow<Object> get() {
                try {
                  return ((Supplier<Flow<Object>>) callback).get();
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
            new BiFunction<RequestContext, IGSpanInfo, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, IGSpanInfo agentSpan) {
                try {
                  return ((BiFunction<RequestContext, IGSpanInfo, Flow<Void>>) callback)
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
      case RESPONSE_HEADER_DONE_ID:
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
      case RESPONSE_HEADER_ID:
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
      case REQUEST_METHOD_URI_RAW_ID:
        return (C)
            new TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, String method, URIDataAdapter adapter) {
                try {
                  return ((TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>)
                          callback)
                      .apply(ctx, method, adapter);
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
      case REQUEST_PATH_PARAMS_ID:
        return (C)
            new BiFunction<RequestContext, Map<String, Object>, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, Map<String, Object> map) {
                try {
                  return ((BiFunction<RequestContext, Map<String, Object>, Flow<Void>>) callback)
                      .apply(ctx, map);
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
            new TriFunction<RequestContext, String, Integer, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, String ip, Integer port) {
                try {
                  return ((TriFunction<RequestContext, String, Integer, Flow<Void>>) callback)
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
      case GRPC_SERVER_REQUEST_MESSAGE_ID:
      case REQUEST_BODY_CONVERTED_ID:
        return (C)
            new BiFunction<RequestContext, Object, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, Object obj) {
                try {
                  return ((BiFunction<RequestContext, Object, Flow<Void>>) callback)
                      .apply(ctx, obj);
                } catch (Throwable t) {
                  log.warn("Callback for {} threw.", eventType, t);
                  return Flow.ResultFlow.empty();
                }
              }
            };
      case RESPONSE_STARTED_ID:
        return (C)
            new BiFunction<RequestContext, Integer, Flow<Void>>() {
              @Override
              public Flow<Void> apply(RequestContext ctx, Integer status) {
                try {
                  return ((BiFunction<RequestContext, Integer, Flow<Void>>) callback)
                      .apply(ctx, status);
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

package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;

import com.aerospike.client.AerospikeException;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.NoopContinuation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts newer batch callbacks without linking their interfaces on older clients. */
public final class BatchTracingListener implements InvocationHandler {
  private static final ClassValue<Boolean> VALIDATED_INTERFACES =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
          validateInterface(type);
          return Boolean.TRUE;
        }
      };

  private final AgentSpan clientSpan;
  private final ContextContinuation continuation;
  private final Object listener;
  private final AtomicBoolean completed = new AtomicBoolean();

  public BatchTracingListener(
      final AgentSpan clientSpan, final ContextContinuation continuation, final Object listener) {
    this.clientSpan = clientSpan;
    this.continuation = continuation;
    this.listener = listener;
  }

  public static void validate(final Class<?> type) {
    VALIDATED_INTERFACES.get(type);
  }

  private static void validateInterface(final Class<?> type) {
    final String name = type.getName();
    final boolean array = "com.aerospike.client.listener.BatchRecordArrayListener".equals(name);
    final boolean sequence =
        "com.aerospike.client.listener.BatchRecordSequenceListener".equals(name);
    final boolean list = "com.aerospike.client.listener.BatchOperateListListener".equals(name);
    final Method[] methods = type.getMethods();
    if (!(array || sequence || list)
        || !type.isInterface()
        || !Modifier.isPublic(type.getModifiers())
        || type.getInterfaces().length != 0
        || methods.length != (sequence ? 3 : 2)) {
      throw new IllegalArgumentException("Unsupported batch listener: " + name);
    }
    for (final Method method : methods) {
      final Class<?>[] parameters = method.getParameterTypes();
      final boolean recordArray =
          parameters.length > 0
              && "[Lcom.aerospike.client.BatchRecord;".equals(parameters[0].getName());
      final boolean valid;
      switch (method.getName()) {
        case "onSuccess":
          valid =
              (sequence && parameters.length == 0)
                  || (parameters.length == 2
                      && parameters[1] == boolean.class
                      && ((array && recordArray) || (list && parameters[0] == List.class)));
          break;
        case "onFailure":
          valid =
              array
                  ? parameters.length == 2
                      && recordArray
                      && parameters[1] == AerospikeException.class
                  : parameters.length == 1 && parameters[0] == AerospikeException.class;
          break;
        case "onRecord":
          valid =
              sequence
                  && parameters.length == 2
                  && "com.aerospike.client.BatchRecord".equals(parameters[0].getName())
                  && parameters[1] == int.class;
          break;
        default:
          valid = false;
      }
      if (!valid
          || method.getReturnType() != void.class
          || !Modifier.isAbstract(method.getModifiers())) {
        throw new IllegalArgumentException("Unsupported batch callback: " + method);
      }
    }
  }

  public Object proxy(final Class<?> listenerType) {
    return Proxy.newProxyInstance(
        listenerType.getClassLoader(), new Class<?>[] {listenerType}, this);
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      switch (method.getName()) {
        case "equals":
          return proxy == args[0];
        case "hashCode":
          return System.identityHashCode(proxy);
        default:
          return "BatchTracingListener@" + Integer.toHexString(System.identityHashCode(proxy));
      }
    }
    if ("onRecord".equals(method.getName())) {
      if (listener != null) {
        try (final ContextScope scope = attachContext()) {
          return forward(method, args);
        }
      }
      return null;
    }
    final boolean terminalOwner = completed.compareAndSet(false, true);
    if (terminalOwner) {
      final Throwable error =
          "onFailure".equals(method.getName()) ? (Throwable) args[args.length - 1] : null;
      try {
        DECORATE.finishAerospikeSpan(clientSpan, error);
      } catch (final Throwable ignored) {
        // Callback delivery still owns the captured context if decoration fails.
      }
    }
    if (listener == null) {
      if (terminalOwner) {
        continuation.release();
      }
      return null;
    }
    try (final ContextScope scope = terminalOwner ? continuation.resume() : attachContext()) {
      return forward(method, args);
    }
  }

  private ContextScope attachContext() {
    return continuation == NoopContinuation.INSTANCE
        ? continuation.resume()
        : continuation.context().attach();
  }

  private Object forward(final Method method, final Object[] args) throws Throwable {
    try {
      return method.invoke(listener, args);
    } catch (final InvocationTargetException error) {
      throw error.getCause();
    }
  }

  public static void closeSubmission(
      final ContextScope scope,
      final BatchTracingListener batch,
      final ContextContinuation continuation,
      final AgentSpan span,
      final Throwable error,
      final boolean abort) {
    final boolean finishSpan =
        abort && span != null && (batch == null || batch.completed.compareAndSet(false, true));
    try {
      if (finishSpan) {
        if (error != null) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span);
      }
    } finally {
      try {
        if (scope != null) {
          scope.close();
        }
      } finally {
        if (finishSpan) {
          try {
            span.finish();
          } finally {
            if (continuation != null) {
              continuation.release();
            }
          }
        }
      }
    }
  }
}

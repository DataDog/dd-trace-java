package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSink;
import datadog.openfeature.internal.core.ConfigurationSource;
import datadog.openfeature.internal.core.SourceStatus;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflective access to the optional agent bridge. */
final class RawBridgeAccess {

  private static final Logger log = LoggerFactory.getLogger(RawBridgeAccess.class);
  private static final String BRIDGE_CLASS =
      "datadog.trace.api.featureflag.FeatureFlaggingRawBridge";
  private static final String CONFIG_LISTENER_CLASS = BRIDGE_CLASS + "$ConfigurationListener";

  private RawBridgeAccess() {}

  static ConfigurationSource remoteConfigurationSource(final ConfigurationSink sink) {
    return new RemoteConfigurationSource(sink);
  }

  static void activateIfPresent() {
    invokeOptional("activate", new Class<?>[0]);
  }

  static void dispatchExposure(
      final long timestamp,
      final String allocationKey,
      final String flagKey,
      final String variantKey,
      final String targetingKey,
      final Map<String, Object> attributes) {
    invokeOptional(
        "dispatchExposure",
        new Class<?>[] {
          long.class, String.class, String.class, String.class, String.class, Map.class
        },
        timestamp,
        allocationKey,
        flagKey,
        variantKey,
        targetingKey,
        attributes);
  }

  static void dispatchSpanSerialId(
      final int serialId, final boolean doLog, final String targetingKey) {
    invokeOptional(
        "dispatchSpanSerialId",
        new Class<?>[] {int.class, boolean.class, String.class},
        serialId,
        doLog,
        targetingKey);
  }

  static void dispatchSpanRuntimeDefault(final String flagKey, final Object value) {
    invokeOptional(
        "dispatchSpanRuntimeDefault", new Class<?>[] {String.class, Object.class}, flagKey, value);
  }

  @SuppressForbidden
  private static void invokeOptional(
      final String methodName, final Class<?>[] parameterTypes, final Object... arguments) {
    try {
      final Class<?> bridge = Class.forName(BRIDGE_CLASS);
      bridge.getMethod(methodName, parameterTypes).invoke(null, arguments);
    } catch (final ClassNotFoundException | NoSuchMethodException ignored) {
      // CDN evaluation works without an agent and with agents released before the raw bridge.
    } catch (final ReflectiveOperationException | LinkageError e) {
      log.debug("Feature Flagging agent bridge call failed: {}", methodName, e);
    }
  }

  private static final class RemoteConfigurationSource implements ConfigurationSource {
    private final ConfigurationSink sink;
    private Class<?> bridge;
    private Method removeListener;
    private Object listener;
    private volatile SourceStatus status = SourceStatus.NEW;

    private RemoteConfigurationSource(final ConfigurationSink sink) {
      this.sink = sink;
    }

    @Override
    @SuppressForbidden
    public synchronized void start() {
      if (status != SourceStatus.NEW) {
        return;
      }
      status = SourceStatus.STARTING;
      try {
        bridge = Class.forName(BRIDGE_CLASS);
        final Class<?> listenerType = Class.forName(CONFIG_LISTENER_CLASS);
        final InvocationHandler handler =
            (proxy, method, arguments) -> {
              if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                  case "equals":
                    return proxy == arguments[0];
                  case "hashCode":
                    return System.identityHashCode(proxy);
                  case "toString":
                    return "DatadogFeatureFlaggingConfigurationListener";
                  default:
                    throw new UnsupportedOperationException(method.getName());
                }
              }
              if ("accept".equals(method.getName())) {
                final byte[] content =
                    arguments == null || arguments.length == 0 ? null : (byte[]) arguments[0];
                if (content == null) {
                  sink.clear();
                } else {
                  sink.apply(content);
                }
                status = SourceStatus.READY;
              }
              return null;
            };
        listener =
            Proxy.newProxyInstance(
                RawBridgeAccess.class.getClassLoader(), new Class<?>[] {listenerType}, handler);
        bridge.getMethod("addConfigurationListener", listenerType).invoke(null, listener);
        removeListener = bridge.getMethod("removeConfigurationListener", listenerType);
        status = SourceStatus.READY;
      } catch (final ClassNotFoundException | NoSuchMethodException e) {
        status = SourceStatus.ERROR;
        throw new IllegalStateException(
            "Remote Configuration requires a Java agent with the Feature Flagging raw bridge "
                + "(version 1.65.0 or later)",
            e);
      } catch (final ReflectiveOperationException | LinkageError e) {
        status = SourceStatus.ERROR;
        throw new IllegalStateException(
            "Remote Configuration could not initialize the Java agent Feature Flagging bridge", e);
      }
    }

    @Override
    public SourceStatus status() {
      return status;
    }

    @Override
    public synchronized void close() {
      if (status == SourceStatus.CLOSED) {
        return;
      }
      try {
        if (removeListener != null && listener != null) {
          removeListener.invoke(null, listener);
        }
      } catch (final ReflectiveOperationException | LinkageError e) {
        log.debug("Feature Flagging agent bridge listener removal failed", e);
      } finally {
        bridge = null;
        removeListener = null;
        listener = null;
        status = SourceStatus.CLOSED;
      }
    }
  }
}

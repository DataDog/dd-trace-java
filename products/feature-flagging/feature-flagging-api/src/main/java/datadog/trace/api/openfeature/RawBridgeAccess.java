package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSink;
import datadog.openfeature.internal.core.ConfigurationSource;
import datadog.openfeature.internal.core.SourceStatus;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reflective access to the optional agent bridge. */
final class RawBridgeAccess {

  private static final Logger log = LoggerFactory.getLogger(RawBridgeAccess.class);
  private static final String BRIDGE_CLASS =
      "datadog.trace.api.featureflag.FeatureFlaggingRawBridge";
  private static final String CONFIG_LISTENER_CLASS = BRIDGE_CLASS + "$ConfigurationListener";
  private static final BridgeMethods BRIDGE_METHODS = BridgeMethods.load();

  private RawBridgeAccess() {}

  static ConfigurationSource remoteConfigurationSource(final ConfigurationSink sink) {
    return new RemoteConfigurationSource(sink);
  }

  static Map<String, Object> runtimeConfiguration() {
    final Method method = BRIDGE_METHODS.getRuntimeConfiguration;
    if (method == null) {
      return Collections.emptyMap();
    }
    try {
      final Object value = method.invoke(null);
      if (!(value instanceof Map)) {
        return Collections.emptyMap();
      }
      final Map<String, Object> configuration = new LinkedHashMap<>();
      for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (entry.getKey() instanceof String) {
          configuration.put((String) entry.getKey(), entry.getValue());
        }
      }
      return configuration;
    } catch (final ReflectiveOperationException | LinkageError e) {
      log.debug("Feature Flagging agent bridge runtime configuration lookup failed", e);
      return Collections.emptyMap();
    }
  }

  static void activateIfPresent() {
    invokeOptional("activate", BRIDGE_METHODS.activate);
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
        BRIDGE_METHODS.dispatchExposure,
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
        "dispatchSpanSerialId", BRIDGE_METHODS.dispatchSpanSerialId, serialId, doLog, targetingKey);
  }

  static void dispatchSpanRuntimeDefault(final String flagKey, final Object value) {
    invokeOptional(
        "dispatchSpanRuntimeDefault", BRIDGE_METHODS.dispatchSpanRuntimeDefault, flagKey, value);
  }

  private static void invokeOptional(
      final String methodName, final Method method, final Object... arguments) {
    if (method == null) {
      return;
    }
    try {
      method.invoke(null, arguments);
    } catch (final ReflectiveOperationException | LinkageError e) {
      log.debug("Feature Flagging agent bridge call failed: {}", methodName, e);
    }
  }

  private static final class RemoteConfigurationSource implements ConfigurationSource {
    private final ConfigurationSink sink;
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
      if (!BRIDGE_METHODS.supportsRemoteConfiguration()) {
        status = SourceStatus.ERROR;
        throw new IllegalStateException(
            "Remote Configuration requires a Java agent with the Feature Flagging raw bridge "
                + "(version 1.65.0 or later)",
            BRIDGE_METHODS.unavailableCause);
      }
      try {
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
                RawBridgeAccess.class.getClassLoader(),
                new Class<?>[] {BRIDGE_METHODS.listenerType},
                handler);
        BRIDGE_METHODS.addConfigurationListener.invoke(null, listener);
        status = SourceStatus.READY;
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
        if (BRIDGE_METHODS.removeConfigurationListener != null && listener != null) {
          BRIDGE_METHODS.removeConfigurationListener.invoke(null, listener);
        }
      } catch (final ReflectiveOperationException | LinkageError e) {
        log.debug("Feature Flagging agent bridge listener removal failed", e);
      } finally {
        listener = null;
        status = SourceStatus.CLOSED;
      }
    }
  }

  private static final class BridgeMethods {
    private final Class<?> listenerType;
    private final Method activate;
    private final Method dispatchExposure;
    private final Method dispatchSpanSerialId;
    private final Method dispatchSpanRuntimeDefault;
    private final Method addConfigurationListener;
    private final Method removeConfigurationListener;
    private final Method getRuntimeConfiguration;
    private final Throwable unavailableCause;

    private BridgeMethods(
        final Class<?> listenerType,
        final Method activate,
        final Method dispatchExposure,
        final Method dispatchSpanSerialId,
        final Method dispatchSpanRuntimeDefault,
        final Method addConfigurationListener,
        final Method removeConfigurationListener,
        final Method getRuntimeConfiguration,
        final Throwable unavailableCause) {
      this.listenerType = listenerType;
      this.activate = activate;
      this.dispatchExposure = dispatchExposure;
      this.dispatchSpanSerialId = dispatchSpanSerialId;
      this.dispatchSpanRuntimeDefault = dispatchSpanRuntimeDefault;
      this.addConfigurationListener = addConfigurationListener;
      this.removeConfigurationListener = removeConfigurationListener;
      this.getRuntimeConfiguration = getRuntimeConfiguration;
      this.unavailableCause = unavailableCause;
    }

    @SuppressForbidden
    private static BridgeMethods load() {
      try {
        final Class<?> bridge = Class.forName(BRIDGE_CLASS);
        final Class<?> listenerType = Class.forName(CONFIG_LISTENER_CLASS);
        return new BridgeMethods(
            listenerType,
            bridge.getMethod("activate"),
            bridge.getMethod(
                "dispatchExposure",
                long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Map.class),
            bridge.getMethod("dispatchSpanSerialId", int.class, boolean.class, String.class),
            bridge.getMethod("dispatchSpanRuntimeDefault", String.class, Object.class),
            bridge.getMethod("addConfigurationListener", listenerType),
            bridge.getMethod("removeConfigurationListener", listenerType),
            bridge.getMethod("getRuntimeConfiguration"),
            null);
      } catch (final ClassNotFoundException | NoSuchMethodException | LinkageError e) {
        // CDN evaluation works without an agent and with agents released before the raw bridge.
        return new BridgeMethods(null, null, null, null, null, null, null, null, e);
      }
    }

    private boolean supportsRemoteConfiguration() {
      return listenerType != null
          && addConfigurationListener != null
          && removeConfigurationListener != null;
    }
  }
}

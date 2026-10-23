package datadog.trace.instrumentation.openfeature;

import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isStatic;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/** The bridge backed by the agent Feature Flags backend. */
public final class JavaAgentBridge implements AgentBridge {
  // Muzzle checks calls, but not the SPI methods implemented by these helpers.
  private static final boolean SPI_COMPATIBLE =
      implementsSpi(AgentBridge.class, JavaAgentBridge.class)
          && implementsSpi(ConfigurationSource.class, AgentConfigurationSource.class)
          && implementsSpi(EventTransport.class, AgentEventProxy.class)
          && implementsSpi(HealthMetrics.class, AgentHealthMetrics.class)
          && implementsSpi(SpanEnricher.class, AgentSpanEnricher.class)
          && implementsSpi(ShutdownSource.class, AgentShutdownSource.class);

  private final FeatureFlaggingGateway.Backend backend;
  private final HealthMetrics healthMetrics;
  private final SpanEnricher spanEnricher;

  JavaAgentBridge(final FeatureFlaggingGateway.Backend backend) {
    this.backend = backend;
    this.healthMetrics = new AgentHealthMetrics(backend);
    this.spanEnricher = new AgentSpanEnricher(backend);
  }

  /**
   * Connects the SDK to the agent backend.
   *
   * @param fallback the bridge to use if the agent backend is not started or the SDK SPI is
   *     incompatible.
   * @return the agent bridge, or the fallback one.
   */
  public static AgentBridge connect(final AgentBridge fallback) {
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    return backend == null || !SPI_COMPATIBLE ? fallback : new JavaAgentBridge(backend);
  }

  private static boolean implementsSpi(final Class<?> spi, final Class<?> implementation) {
    try {
      for (final Method method : spi.getMethods()) {
        if (!isStatic(method.getModifiers())) {
          final Method implemented =
              implementation.getMethod(method.getName(), method.getParameterTypes());
          if (isAbstract(implemented.getModifiers())
              || isStatic(implemented.getModifiers())
              || implemented.getReturnType() != method.getReturnType()) {
            return false;
          }
        }
      }
      return true;
    } catch (final NoSuchMethodException e) {
      return false;
    }
  }

  @Nullable
  @Override
  public String setting(final String key) {
    return this.backend.setting(key);
  }

  @Nullable
  @Override
  public ConfigurationSource remoteConfiguration() {
    return this.backend.isRemoteConfigAvailable()
        ? new AgentConfigurationSource(this.backend)
        : null;
  }

  @Nullable
  @Override
  public EventTransport eventProxy() {
    return this.backend.isEventProxyAvailable() ? new AgentEventProxy(this.backend) : null;
  }

  @Override
  public HealthMetrics healthMetrics() {
    return this.healthMetrics;
  }

  @Override
  public SpanEnricher spanEnricher() {
    return this.spanEnricher;
  }

  @Override
  public ShutdownSource shutdown() {
    return new AgentShutdownSource(this.backend);
  }
}

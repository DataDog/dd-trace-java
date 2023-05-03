package datadog.trace.api;

import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;

import java.util.Collections;
import java.util.Map;

/** Manages dynamic configuration for a particular {@link Tracer} instance. */
public final class DynamicConfig {
  private volatile State currentState;

  private DynamicConfig() {}

  public static Builder create() {
    return new DynamicConfig().prepare();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public TraceConfig captureTraceConfig() {
    return currentState;
  }

  public Builder prepare() {
    return new Builder(currentState);
  }

  public final class Builder {
    Map<String, String> serviceMapping;

    Builder(State state) {
      if (null == state) {
        this.serviceMapping = Collections.emptyMap();
      } else {
        this.serviceMapping = state.serviceMapping;
      }
    }

    public Builder setServiceMapping(Map<String, String> serviceMapping) {
      this.serviceMapping = tryMakeImmutableMap(serviceMapping);
      return this;
    }

    /** Overwrites the current configuration with a new snapshot. */
    public DynamicConfig apply() {
      DynamicConfig.this.currentState = new State(this);
      return DynamicConfig.this;
    }
  }

  /** Immutable snapshot of the configuration. */
  static final class State implements TraceConfig {
    final Map<String, String> serviceMapping;

    State(Builder builder) {
      this.serviceMapping = builder.serviceMapping;
    }

    public Map<String, String> getServiceMapping() {
      return serviceMapping;
    }
  }
}

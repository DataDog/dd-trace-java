package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.instrumentation.MetricInstrumentor;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.Type;

/** Stores definition of a metric probe */
public class MetricProbe extends ProbeDefinition {

  public enum MetricKind {
    COUNT {
      @Override
      public boolean isCompatible(Type type) {
        return isLongOnlyCompatible(type);
      }

      @Override
      public Collection<Type> getSupportedTypes() {
        return Collections.singleton(Type.LONG_TYPE);
      }
    },
    GAUGE {
      @Override
      public boolean isCompatible(Type type) {
        return isLongAndDoubleCompatible(type);
      }

      @Override
      public Collection<Type> getSupportedTypes() {
        return Arrays.asList(Type.LONG_TYPE, Type.DOUBLE_TYPE);
      }
    },
    HISTOGRAM {
      @Override
      public boolean isCompatible(Type type) {
        return isLongAndDoubleCompatible(type);
      }

      @Override
      public Collection<Type> getSupportedTypes() {
        return Arrays.asList(Type.LONG_TYPE, Type.DOUBLE_TYPE);
      }
    },
    DISTRIBUTION {
      @Override
      public boolean isCompatible(Type type) {
        return isLongAndDoubleCompatible(type);
      }

      @Override
      public Collection<Type> getSupportedTypes() {
        return Arrays.asList(Type.LONG_TYPE, Type.DOUBLE_TYPE);
      }
    };

    public abstract boolean isCompatible(Type type);

    public abstract Collection<Type> getSupportedTypes();

    protected boolean isLongOnlyCompatible(Type type) {
      switch (type.getSort()) {
        case Type.BYTE:
        case Type.SHORT:
        case Type.CHAR:
        case Type.INT:
        case Type.LONG:
        case Type.BOOLEAN:
          return true;
      }
      return false;
    }

    protected boolean isLongAndDoubleCompatible(Type type) {
      switch (type.getSort()) {
        case Type.BYTE:
        case Type.SHORT:
        case Type.CHAR:
        case Type.INT:
        case Type.LONG:
        case Type.FLOAT:
        case Type.DOUBLE:
        case Type.BOOLEAN:
          return true;
      }
      return false;
    }
  }

  private final MetricKind kind;
  private final String metricName;
  private final ValueScript value;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public MetricProbe() {
    this(LANGUAGE, null, null, null, MethodLocation.DEFAULT, MetricKind.COUNT, null, null);
  }

  public MetricProbe(
      String language,
      ProbeId probeId,
      String[] tagStrs,
      Where where,
      MethodLocation evaluateAt,
      MetricKind kind,
      String metricName,
      ValueScript value) {
    super(language, probeId, tagStrs, where, evaluateAt);
    this.kind = kind;
    this.metricName = metricName;
    this.value = value;
  }

  public MetricKind getKind() {
    return kind;
  }

  public String getMetricName() {
    return metricName;
  }

  public ValueScript getValue() {
    return value;
  }

  @Override
  public InstrumentationResult.Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<Integer> probeIndices) {
    return new MetricInstrumentor(this, methodInfo, diagnostics, probeIndices).instrument();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    private MetricKind kind;
    private String metricName;
    private ValueScript valueScript;

    public Builder kind(MetricKind kind) {
      this.kind = kind;
      return this;
    }

    public Builder metricName(String name) {
      this.metricName = name;
      return this;
    }

    public Builder valueScript(ValueScript valueScript) {
      this.valueScript = valueScript;
      return this;
    }

    public MetricProbe build() {
      return new MetricProbe(
          language, probeId, tagStrs, where, evaluateAt, kind, metricName, valueScript);
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetricProbe that = (MetricProbe) o;
    return Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && version == that.version
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt)
        && kind == that.kind
        && Objects.equals(metricName, that.metricName)
        && Objects.equals(value, that.value);
  }

  @Generated
  @Override
  public int hashCode() {
    int result =
        Objects.hash(language, id, version, tagMap, where, evaluateAt, kind, metricName, value);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "MetricProbe{"
        + "id='"
        + id
        + '\''
        + ", version="
        + version
        + ", tags="
        + Arrays.toString(tags)
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + ", kind="
        + kind
        + ", metricName='"
        + metricName
        + ", valueScript='"
        + value
        + '\''
        + "} ";
  }
}

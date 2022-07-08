package com.datadog.debugger.agent;

import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.MetricInstrumentor;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Stores definition of a metric probe */
public class MetricProbe extends ProbeDefinition {

  public enum MetricKind {
    COUNT,
    GAUGE,
    HISTOGRAM
  }

  private final MetricKind kind;
  private final String metricName;
  private final ValueScript value;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public MetricProbe() {
    this(LANGUAGE, null, true, null, null, MetricKind.COUNT, null, null);
  }

  public MetricProbe(
      String language,
      String probeId,
      boolean active,
      String[] tagStrs,
      Where where,
      MetricKind kind,
      String metricName,
      ValueScript value) {
    super(language, probeId, active, tagStrs, where);
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
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    new MetricInstrumentor(this, classLoader, classNode, methodNode, diagnostics).instrument();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String language = LANGUAGE;
    private String metricId;
    private boolean active = true;
    private String[] tagStrs;
    private Where where;
    private MetricKind kind;
    private String metricName;
    private ValueScript valueScript;

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public Builder metricId(String metricId) {
      this.metricId = metricId;
      return this;
    }

    public Builder active(boolean active) {
      this.active = active;
      return this;
    }

    public Builder tags(String... tagStrs) {
      this.tagStrs = tagStrs;
      return this;
    }

    public Builder where(Where where) {
      this.where = where;
      return this;
    }

    public Builder where(String typeName, String methodName) {
      return where(new Where(typeName, methodName, null, (Where.SourceLine[]) null, null));
    }

    public Builder where(String typeName, String methodName, String signature) {
      return where(new Where(typeName, methodName, signature, (Where.SourceLine[]) null, null));
    }

    public Builder where(String typeName, String methodName, String signature, String... lines) {
      return where(new Where(typeName, methodName, signature, Where.sourceLines(lines), null));
    }

    public Builder where(String sourceFile, String... lines) {
      return where(new Where(null, null, null, lines, sourceFile));
    }

    public Builder where(
        String typeName, String methodName, String signature, int codeLine, String source) {
      return where(
          new Where(
              typeName,
              methodName,
              signature,
              new Where.SourceLine[] {new Where.SourceLine(codeLine)},
              source));
    }

    public Builder where(
        String typeName,
        String methodName,
        String signature,
        int codeLineFrom,
        int codeLineTill,
        String source) {
      return where(
          new Where(
              typeName,
              methodName,
              signature,
              new Where.SourceLine[] {new Where.SourceLine(codeLineFrom, codeLineTill)},
              source));
    }

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
          language, metricId, active, tagStrs, where, kind, metricName, valueScript);
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetricProbe that = (MetricProbe) o;
    return active == that.active
        && Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && kind == that.kind
        && Objects.equals(metricName, that.metricName)
        && Objects.equals(value, that.value);
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, active, tagMap, where, kind, metricName, value);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "MetricProbe{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", active="
        + active
        + ", tags="
        + Arrays.toString(tags)
        + ", tagMap="
        + tagMap
        + ", where="
        + where
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

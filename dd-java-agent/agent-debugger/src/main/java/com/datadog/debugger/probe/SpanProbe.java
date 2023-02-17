package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.SpanInstrumentor;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class SpanProbe extends ProbeDefinition {
  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public SpanProbe() {
    this(LANGUAGE, null, true, null, null);
  }

  public SpanProbe(String language, String id, boolean active, String[] tagStrs, Where where) {
    super(language, id, active, tagStrs, where, MethodLocation.DEFAULT);
  }

  @Override
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    new SpanInstrumentor(this, classLoader, classNode, methodNode, diagnostics).instrument();
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, active, tagMap, where, evaluateAt);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SpanProbe that = (SpanProbe) o;
    return active == that.active
        && Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt);
  }

  @Generated
  @Override
  public String toString() {
    return "SpanProbe{"
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
        + ", evaluateAt="
        + evaluateAt
        + "} ";
  }

  public static SpanProbe.Builder builder() {
    return new SpanProbe.Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<Builder> {
    public SpanProbe build() {
      return new SpanProbe(LANGUAGE, probeId, active, tagStrs, where);
    }
  }
}

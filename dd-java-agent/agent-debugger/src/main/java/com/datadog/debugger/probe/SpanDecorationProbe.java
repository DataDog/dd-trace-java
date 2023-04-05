package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.SummaryBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class SpanDecorationProbe extends ProbeDefinition {
  public enum TargetSpan {
    ACTIVE,
    ROOT
  }

  public static class Tag {
    private final String tagName;
    private final ValueScript tagValue;

    public Tag(String tagName, ValueScript tagValue) {
      this.tagName = tagName;
      this.tagValue = tagValue;
    }

    public String getTagName() {
      return tagName;
    }

    public ValueScript getTagValue() {
      return tagValue;
    }
  }

  public static class Decoration {
    private final ProbeCondition when;
    private final List<Tag> tags;

    public Decoration(ProbeCondition when, List<Tag> tags) {
      this.when = when;
      this.tags = tags;
    }

    public ProbeCondition getWhen() {
      return when;
    }

    public List<Tag> getTags() {
      return tags;
    }
  }

  private final TargetSpan targetSpan;
  private final List<Decoration> decorate;

  // no-arg constructor is required by Moshi to avoid creating instance with unsafe and by-passing
  // constructors, including field initializers.
  public SpanDecorationProbe() {
    this(LANGUAGE, null, null, null, MethodLocation.DEFAULT, TargetSpan.ACTIVE, null);
  }

  public SpanDecorationProbe(
      String language,
      ProbeId probeId,
      String[] tagStrs,
      Where where,
      MethodLocation methodLocation,
      TargetSpan targetSpan,
      List<Decoration> decorate) {
    super(language, probeId, tagStrs, where, methodLocation);
    this.targetSpan = targetSpan;
    this.decorate = decorate;
  }

  @Override
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {}

  @Override
  public SummaryBuilder getSummaryBuilder() {
    return null;
  }

  public TargetSpan getTargetSpan() {
    return targetSpan;
  }

  public List<Decoration> getDecorate() {
    return decorate;
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, version, tagMap, where, evaluateAt);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SpanDecorationProbe that = (SpanDecorationProbe) o;
    return Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && version == that.version
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt);
  }

  @Generated
  @Override
  public String toString() {
    return "SpanDecorationProbe{"
        + "language='"
        + language
        + '\''
        + ", id='"
        + id
        + '\''
        + ", version="
        + version
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

  public static SpanDecorationProbe.Builder builder() {
    return new SpanDecorationProbe.Builder();
  }

  public static class Builder extends ProbeDefinition.Builder<SpanDecorationProbe.Builder> {
    private TargetSpan targetSpan;
    private List<Decoration> decorate;

    public Builder targetSpan(TargetSpan targetSpan) {
      this.targetSpan = targetSpan;
      return this;
    }

    public Builder decorate(List<Decoration> decorate) {
      this.decorate = decorate;
      return this;
    }

    public Builder decorate(Decoration decoration) {
      this.decorate = Collections.singletonList(decoration);
      return this;
    }

    public SpanDecorationProbe build() {
      return new SpanDecorationProbe(
          LANGUAGE, probeId, tagStrs, where, evaluateAt, targetSpan, decorate);
    }
  }
}

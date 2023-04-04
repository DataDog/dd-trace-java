package com.datadog.debugger.probe;

import static com.datadog.debugger.util.ValueScriptHelper.serializeValue;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.SpanDecorationInstrumentor;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpanDecorationProbe extends ProbeDefinition {
  private static final Logger LOGGER = LoggerFactory.getLogger(SpanDecorationProbe.class);

  public enum TargetSpan {
    ACTIVE,
    ROOT
  }

  public static class Tag {
    private final String name;
    private final ValueScript value;

    public Tag(String name, ValueScript value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public ValueScript getValue() {
      return value;
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
  private final List<Decoration> decorations;

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
      List<Decoration> decorations) {
    super(language, probeId, tagStrs, where, methodLocation);
    this.targetSpan = targetSpan;
    this.decorations = decorations;
  }

  @Override
  public void instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    new SpanDecorationInstrumentor(this, classLoader, classNode, methodNode, diagnostics, probeIds)
        .instrument();
  }

  @Override
  public void evaluate(
      Snapshot.CapturedContext context,
      Snapshot.CapturedContext.Status status,
      MethodLocation methodLocation) {
    boolean shouldEvaluate = resolveEvaluateAt(methodLocation);
    if (!shouldEvaluate) {
      return;
    }
    for (Decoration decoration : decorations) {
      try {
        if (decoration.when != null) {
          boolean condition = decoration.when.execute(context);
          if (!condition) {
            continue;
          }
        }
        for (Tag tag : decoration.tags) {
          Value<?> tagValue = tag.value.execute(context);
          StringBuilder sb = new StringBuilder();
          if (tagValue.isUndefined()) {
            sb.append(tagValue.getValue());
          } else if (tagValue.isNull()) {
            sb.append("null");
          } else {
            serializeValue(sb, tag.value.getDsl(), tagValue.getValue(), status);
          }
          status.addTag(tag.name, sb.toString());
        }
      } catch (EvaluationException ex) {
        LOGGER.debug("Evaluation error: ", ex);
        status.addError(new Snapshot.EvaluationError(ex.getExpr(), ex.getMessage()));
      }
    }
  }

  @Override
  public void commit(
      Snapshot.CapturedContext entryContext,
      Snapshot.CapturedContext exitContext,
      List<Snapshot.CapturedThrowable> caughtExceptions) {
    Snapshot.CapturedContext.Status status = null;
    if (evaluateAt == MethodLocation.ENTRY || evaluateAt == MethodLocation.DEFAULT) {
      status = entryContext.getStatus(id);
    } else if (evaluateAt == MethodLocation.EXIT) {
      status = exitContext.getStatus(id);
    }
    if (status == null) {
      return;
    }
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan agentSpan = tracerAPI.activeSpan();
    if (targetSpan == TargetSpan.ROOT) {
      agentSpan = agentSpan.getLocalRootSpan();
    }
    List<Pair<String, String>> tagsToDecorate = status.getTagsToDecorate();
    if (tagsToDecorate == null) {
      return;
    }
    for (Pair<String, String> tag : tagsToDecorate) {
      agentSpan.setTag(tag.getLeft(), tag.getRight());
    }
  }

  public TargetSpan getTargetSpan() {
    return targetSpan;
  }

  public List<Decoration> getDecorations() {
    return decorations;
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

package com.datadog.debugger.probe;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.agent.LogMessageTemplateBuilder;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.TagsHelper;
import java.util.ArrayList;
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
  private static final String PROBEID_DD_TAGS_FORMAT = "_dd.di.%s.probe_id";
  private static final String EVALERROR_DD_TAGS_FORMAT = "_dd.di.%s.evaluation_error";

  public enum TargetSpan {
    ACTIVE,
    ROOT
  }

  public static class TagValue {
    private final String template;
    private final List<LogProbe.Segment> segments;

    public TagValue(String template, List<LogProbe.Segment> segments) {
      this.template = template;
      this.segments = segments;
    }

    public String getTemplate() {
      return template;
    }

    public List<LogProbe.Segment> getSegments() {
      return segments;
    }

    @Override
    public String toString() {
      return "TagValue{" + "template='" + template + '\'' + ", segments=" + segments + '}';
    }
  }

  public static class Tag {
    private final String name;
    private final TagValue value;

    public Tag(String name, TagValue value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public TagValue getValue() {
      return value;
    }

    @Generated
    @Override
    public String toString() {
      return "Tag{" + "name='" + name + '\'' + ", value=" + value + '}';
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

    @Generated
    @Override
    public String toString() {
      return "Decoration{" + "when=" + when + ", tags=" + tags + '}';
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
  public InstrumentationResult.Status instrument(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics,
      List<String> probeIds) {
    return new CapturedContextInstrumentor(
            this, classLoader, classNode, methodNode, diagnostics, probeIds, false, Limits.DEFAULT)
        .instrument();
  }

  @Override
  public void evaluate(CapturedContext context, CapturedContext.Status status) {
    for (Decoration decoration : decorations) {
      if (decoration.when != null) {
        try {
          boolean condition = decoration.when.execute(context);
          if (!condition) {
            continue;
          }
        } catch (EvaluationException ex) {
          LOGGER.debug("Evaluation error: ", ex);
          status.addError(new EvaluationError(ex.getExpr(), ex.getMessage()));
          continue;
        }
      }
      if (!(status instanceof SpanDecorationStatus)) {
        throw new IllegalStateException("Invalid status: " + status.getClass());
      }
      SpanDecorationStatus spanStatus = (SpanDecorationStatus) status;
      for (Tag tag : decoration.tags) {
        String tagName = sanitize(tag.name);
        LogMessageTemplateBuilder builder = new LogMessageTemplateBuilder(tag.value.getSegments());
        LogProbe.LogStatus logStatus = new LogProbe.LogStatus(this);
        String tagValue = builder.evaluate(context, logStatus);
        if (logStatus.hasLogTemplateErrors()) {
          status.getErrors().addAll(logStatus.getErrors());
          if (logStatus.getErrors().size() > 0) {
            spanStatus.addTag(
                String.format(EVALERROR_DD_TAGS_FORMAT, tagName),
                logStatus.getErrors().get(0).getMessage());
          }
        } else {
          spanStatus.addTag(tagName, tagValue);
        }
        spanStatus.addTag(String.format(PROBEID_DD_TAGS_FORMAT, tagName), getProbeId().getId());
      }
    }
  }

  private String sanitize(String tagName) {
    return TagsHelper.sanitize(tagName);
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {
    CapturedContext.Status status =
        evaluateAt == MethodLocation.EXIT ? exitContext.getStatus(id) : entryContext.getStatus(id);
    if (status == null) {
      return;
    }
    SpanDecorationStatus spanStatus = (SpanDecorationStatus) status;
    decorateTags(spanStatus);
    handleEvaluationErrors(spanStatus);
  }

  @Override
  public void commit(CapturedContext lineContext, int line) {
    CapturedContext.Status status = lineContext.getStatus(id);
    if (status == null) {
      return;
    }
    SpanDecorationStatus spanStatus = (SpanDecorationStatus) status;
    decorateTags(spanStatus);
    handleEvaluationErrors(spanStatus);
  }

  private void decorateTags(SpanDecorationStatus status) {
    List<Pair<String, String>> tagsToDecorate = status.getTagsToDecorate();
    if (tagsToDecorate == null) {
      return;
    }
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan agentSpan = tracerAPI.activeSpan();
    if (agentSpan == null) {
      LOGGER.debug("Cannot find current active span");
      return;
    }
    if (targetSpan == TargetSpan.ROOT) {
      agentSpan = agentSpan.getLocalRootSpan();
      if (agentSpan == null) {
        LOGGER.debug("Cannot find root span");
        return;
      }
    }
    for (Pair<String, String> tag : tagsToDecorate) {
      agentSpan.setTag(tag.getLeft(), tag.getRight());
    }
  }

  private void handleEvaluationErrors(SpanDecorationStatus status) {
    if (status.getErrors().isEmpty()) {
      return;
    }
    Snapshot snapshot = new Snapshot(Thread.currentThread(), this, -1);
    snapshot.addEvaluationErrors(status.getErrors());
    DebuggerAgent.getSink().addSnapshot(snapshot);
  }

  @Override
  public CapturedContext.Status createStatus() {
    return new SpanDecorationStatus(this);
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
    int result =
        Objects.hash(language, id, version, tagMap, where, evaluateAt, targetSpan, decorations);
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
        && Objects.equals(evaluateAt, that.evaluateAt)
        && Objects.equals(targetSpan, that.targetSpan)
        && Objects.equals(decorations, that.decorations);
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
        + ", targetSpan="
        + targetSpan
        + ", decorations="
        + decorations
        + "} ";
  }

  private static class SpanDecorationStatus extends CapturedContext.Status {
    private final List<Pair<String, String>> tagsToDecorate = new ArrayList<>();

    public SpanDecorationStatus(ProbeImplementation probeImplementation) {
      super(probeImplementation);
    }

    public void addTag(String tagName, String tagValue) {
      tagsToDecorate.add(Pair.of(tagName, tagValue));
    }

    public List<Pair<String, String>> getTagsToDecorate() {
      return tagsToDecorate;
    }
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

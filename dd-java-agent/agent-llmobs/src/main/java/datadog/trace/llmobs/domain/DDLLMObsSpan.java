package datadog.trace.llmobs.domain;

import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsPropagationAccess;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLLMObsSpan implements LLMObsSpan {
  private static final String LLM_MESSAGE_UNKNOWN_ROLE = "unknown";

  // Well known tags for LLM obs will be prefixed with _ml_obs_(tags|metrics).
  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  // internal tags to be prefixed
  private static final String INPUT = LLMOBS_TAG_PREFIX + "input";
  private static final String OUTPUT = LLMOBS_TAG_PREFIX + "output";
  private static final String INPUT_PROMPT = LLMOBS_TAG_PREFIX + "input_prompt";
  private static final String SPAN_KIND = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;
  private static final String METADATA = LLMOBS_TAG_PREFIX + LLMObsTags.METADATA;
  private static final String TOOL_DEFINITIONS = LLMOBS_TAG_PREFIX + LLMObsTags.TOOL_DEFINITIONS;
  private static final String AGENT_MANIFEST = LLMOBS_TAG_PREFIX + LLMObsTags.AGENT_MANIFEST;
  private static final String MANUAL_FRAMEWORK = "manual";
  private static final String PROMPT_TRACKING_INSTRUMENTATION_METHOD =
      LLMOBS_TAG_PREFIX + "prompt_tracking_instrumentation_method";
  private static final String INSTRUMENTATION_METHOD_ANNOTATED = "annotated";
  private static final String DEFAULT_PROMPT_NAME = "unnamed-prompt";
  private static final String CONTEXT_VARIABLE_KEYS = "_dd_context_variable_keys";
  private static final String QUERY_VARIABLE_KEYS = "_dd_query_variable_keys";
  private static final String PARENT_ID_TAG_INTERNAL = "parent_id";
  private static final String PAGENT_SPAN_ID_TAG_INTERNAL =
      LLMOBS_TAG_PREFIX + "pagent_span_id";
  private static final String PAGENT_NAME_TAG_INTERNAL = LLMOBS_TAG_PREFIX + "pagent_name";

  private static final String SERVICE = LLMOBS_TAG_PREFIX + "service";
  private static final String VERSION = LLMOBS_TAG_PREFIX + "version";
  private static final String DDTRACE_VERSION = LLMOBS_TAG_PREFIX + "ddtrace.version";
  private static final String ENV = LLMOBS_TAG_PREFIX + "env";

  private static final String LLM_OBS_INSTRUMENTATION_NAME = "llmobs";

  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsSpan.class);

  private final AgentSpan span;
  private final String spanKind;
  private final String mlApp;
  private final ContextScope scope;
  private final boolean hasSessionId;
  private final boolean hasAgentVersion;

  private boolean finished = false;

  public DDLLMObsSpan(
      @Nonnull String kind,
      String spanName,
      @Nonnull String mlApp,
      String sessionId,
      @Nonnull String serviceName,
      WellKnownTags wellKnownTags) {
    this(kind, spanName, mlApp, sessionId, serviceName, wellKnownTags, null);
  }

  public DDLLMObsSpan(
      @Nonnull String kind,
      String spanName,
      @Nonnull String mlApp,
      String sessionId,
      @Nonnull String serviceName,
      WellKnownTags wellKnownTags,
      String agentVersion) {

    if (null == spanName || spanName.isEmpty()) {
      spanName = kind;
    }

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(LLM_OBS_INSTRUMENTATION_NAME, spanName)
            .withServiceName(serviceName)
            .withSpanType(DDSpanTypes.LLMOBS);

    span = spanBuilder.start();

    // set global dd_tags as base layer so UST and span-level tags can override them
    for (Map.Entry<String, String> entry : Config.get().getGlobalTags().entrySet()) {
      span.setTag(LLMOBS_TAG_PREFIX + entry.getKey(), entry.getValue());
    }

    // set UST (unified service tags, env, service, version)
    span.setTag(ENV, wellKnownTags.getEnv());
    span.setTag(SERVICE, wellKnownTags.getService());
    span.setTag(VERSION, wellKnownTags.getVersion());
    span.setTag(DDTRACE_VERSION, DDTraceApiInfo.VERSION);

    span.setTag(SPAN_KIND, kind);
    spanKind = kind;
    this.mlApp = mlApp;
    span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.ML_APP, mlApp);
    // Resolve effective parent_id and session_id from the LLMObs context, both gated on
    // trace-id consistency. A stale context from a different trace (e.g. async boundary
    // leakage) must not contribute either tag.
    AgentSpanContext parent = LLMObsContext.current();
    String parentSpanID = LLMObsContext.ROOT_SPAN_ID;
    String resolvedAgentVersion = agentVersion;
    if (null != parent) {
      if (parent.getTraceId() != span.getTraceId()) {
        LOGGER.error(
            "trace ID mismatch, retrieved parent from context trace_id={}, span_id={}, started span trace_id={}, span_id={}",
            parent.getTraceId(),
            parent.getSpanId(),
            span.getTraceId(),
            span.getSpanId());
      } else {
        parentSpanID = String.valueOf(parent.getSpanId());
        // Inherit session_id from parent context only when it belongs to the same trace.
        // Matches dd-trace-py and dd-trace-js: session_id need only be set on the root
        // span; descendants inherit transitively via context propagation.
        if (sessionId == null || sessionId.isEmpty()) {
          String inherited = LLMObsContext.currentSessionId();
          if (inherited != null && !inherited.isEmpty()) {
            sessionId = inherited;
          }
        }
        // Inherit agent_version from the enclosing agent span, if this span doesn't set its own.
        // An explicit value always wins, so a nested agent's own version overrides an ancestor's
        // for its own subtree, matching session_id's explicit-wins semantics.
        if (agentVersion == null || agentVersion.isEmpty()) {
          String inherited = LLMObsContext.currentAgentVersion();
          if (inherited != null && !inherited.isEmpty()) {
            resolvedAgentVersion = inherited;
          }
        }
      }
    }

    this.hasSessionId = sessionId != null && !sessionId.isEmpty();
    if (this.hasSessionId) {
      span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID, sessionId);
    }
    this.hasAgentVersion = resolvedAgentVersion != null && !resolvedAgentVersion.isEmpty();
    if (this.hasAgentVersion) {
      span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.AGENT_VERSION, resolvedAgentVersion);
    }
    span.setTag(LLMOBS_TAG_PREFIX + PARENT_ID_TAG_INTERNAL, parentSpanID);

    // Resolve agent attribution (O(1)): identify the nearest agent-kind ancestor.
    String resolvedPagentSpanId = null;
    String resolvedPagentName = null;

    if (Tags.LLMOBS_AGENT_SPAN_KIND.equals(kind)) {
      // This span is itself an agent — it becomes the nearest ancestor for its descendants.
      resolvedPagentSpanId = String.valueOf(span.getSpanId());
      resolvedPagentName = agentNameWireSafe(spanName) ? spanName : null;
    } else {
      // Inherit from in-process LLMObs parent (set by a parent agent span via context).
      resolvedPagentSpanId = LLMObsContext.currentParentAgentSpanId();
      resolvedPagentName = LLMObsContext.currentParentAgentName();

      // Fall back to distributed propagated tags on the root APM span context.
      if (resolvedPagentSpanId == null) {
        AgentSpanContext rootCtx = span.getLocalRootSpan().spanContext();
        if (rootCtx instanceof LLMObsPropagationAccess) {
          LLMObsPropagationAccess access = (LLMObsPropagationAccess) rootCtx;
          resolvedPagentSpanId = access.getParentAgentSpanId();
          resolvedPagentName = access.getParentAgentName();
        }
      }
    }

    // Store pagent values as internal tags so the serializer can emit agent_attribution.
    if (resolvedPagentSpanId != null) {
      span.setTag(PAGENT_SPAN_ID_TAG_INTERNAL, resolvedPagentSpanId);
      if (resolvedPagentName != null) {
        span.setTag(PAGENT_NAME_TAG_INTERNAL, resolvedPagentName);
      }
    }

    // If this span is an agent, stamp the pagent propagation tags for outgoing distributed calls.
    if (Tags.LLMOBS_AGENT_SPAN_KIND.equals(kind) && resolvedPagentSpanId != null) {
      AgentSpanContext rootCtx = span.getLocalRootSpan().spanContext();
      if (rootCtx instanceof LLMObsPropagationAccess) {
        LLMObsPropagationAccess access = (LLMObsPropagationAccess) rootCtx;
        access.setParentAgentSpanId(resolvedPagentSpanId);
        if (resolvedPagentName != null) {
          access.setParentAgentName(resolvedPagentName);
        }
      }
    }

    // Propagate sessionId, agent_version, and agent attribution to descendant LLMObs spans.
    scope =
        LLMObsContext.attach(
            span.spanContext(), sessionId, resolvedAgentVersion, resolvedPagentSpanId,
            resolvedPagentName);
  }

  /**
   * Returns true if the agent name is safe to include in the x-datadog-tags header: printable ASCII
   * only (0x20–0x7E), no commas (delimiter), no semicolons. Max 256 UTF-8 bytes.
   */
  private static boolean agentNameWireSafe(String name) {
    if (name == null) {
      return false;
    }
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 256) {
      return false;
    }
    for (byte b : bytes) {
      int u = b & 0xFF;
      if (u < 0x20 || u > 0x7E || u == 0x2C || u == 0x3B) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return super.toString()
        + ", trace_id="
        + span.spanContext().getTraceId()
        + ", span_id="
        + span.spanContext().getSpanId()
        + ", ml_app="
        + span.getTag(LLMObsTags.ML_APP)
        + ", service="
        + span.getServiceName()
        + ", span_kind="
        + span.getTag(SPAN_KIND);
  }

  @Override
  public void annotateIO(List<LLMObs.LLMMessage> inputData, List<LLMObs.LLMMessage> outputData) {
    if (finished) {
      return;
    }
    if (inputData != null && !inputData.isEmpty()) {
      span.setTag(INPUT, inputData);
    }
    if (outputData != null && !outputData.isEmpty()) {
      span.setTag(OUTPUT, outputData);
    }
  }

  @Override
  public void annotateEmbeddingIO(List<LLMObs.Document> inputDocuments, String outputData) {
    if (finished) {
      return;
    }
    if (inputDocuments != null && !inputDocuments.isEmpty()) {
      span.setTag(INPUT, inputDocuments);
    }
    if (outputData != null && !outputData.isEmpty()) {
      span.setTag(OUTPUT, outputData);
    }
  }

  @Override
  public void annotateRetrievalIO(String inputData, List<LLMObs.Document> outputDocuments) {
    if (finished) {
      return;
    }
    if (inputData != null && !inputData.isEmpty()) {
      span.setTag(INPUT, inputData);
    }
    if (outputDocuments != null && !outputDocuments.isEmpty()) {
      span.setTag(OUTPUT, outputDocuments);
    }
  }

  @Override
  public void annotateIO(String inputData, String outputData) {
    if (finished) {
      return;
    }
    boolean hasInput = inputData != null && !inputData.isEmpty();
    boolean hasOutput = outputData != null && !outputData.isEmpty();
    if (Tags.LLMOBS_LLM_SPAN_KIND.equals(spanKind)) {
      List<LLMObs.LLMMessage> inputMessages =
          hasInput
              ? Collections.singletonList(
                  LLMObs.LLMMessage.from(LLM_MESSAGE_UNKNOWN_ROLE, inputData))
              : null;
      List<LLMObs.LLMMessage> outputMessages =
          hasOutput
              ? Collections.singletonList(
                  LLMObs.LLMMessage.from(LLM_MESSAGE_UNKNOWN_ROLE, outputData))
              : null;
      annotateIO(inputMessages, outputMessages);
      if (hasInput || hasOutput) {
        LOGGER.warn(
            "the span being annotated is an LLM span, it is recommended to use the overload with List<LLMObs.LLMMessage> as arguments");
      }
      return;
    }
    if (Tags.LLMOBS_EMBEDDING_SPAN_KIND.equals(spanKind)) {
      List<LLMObs.Document> inputDocuments =
          hasInput ? Collections.singletonList(LLMObs.Document.from(inputData)) : null;
      annotateEmbeddingIO(inputDocuments, outputData);
      if (hasInput) {
        LOGGER.warn(
            "the span being annotated is an embedding span, it is recommended to use annotateEmbeddingIO");
      }
      return;
    }
    if (Tags.LLMOBS_RETRIEVAL_SPAN_KIND.equals(spanKind)) {
      List<LLMObs.Document> outputDocuments =
          hasOutput ? Collections.singletonList(LLMObs.Document.from(outputData)) : null;
      annotateRetrievalIO(inputData, outputDocuments);
      if (hasOutput) {
        LOGGER.warn(
            "the span being annotated is a retrieval span, it is recommended to use annotateRetrievalIO");
      }
      return;
    }
    if (hasInput) {
      span.setTag(INPUT, inputData);
    }
    if (hasOutput) {
      span.setTag(OUTPUT, outputData);
    }
  }

  @Override
  public void annotatePrompt(LLMObs.Prompt prompt) {
    if (finished || prompt == null) {
      return;
    }
    if (!Tags.LLMOBS_LLM_SPAN_KIND.equals(spanKind)) {
      LOGGER.warn(
          "dropping prompt on non-LLM span kind, annotating prompts is only supported for LLM span kinds");
      return;
    }

    Map<String, Object> annotatedPrompt = new LinkedHashMap<>();
    Object currentPrompt = span.getTag(INPUT_PROMPT);
    if (currentPrompt instanceof Map) {
      annotatedPrompt.putAll(copyStringKeyedMap((Map<?, ?>) currentPrompt));
    }
    if (prompt.getId() != null && !prompt.getId().isEmpty()) {
      annotatedPrompt.put("id", prompt.getId());
    }
    if (!annotatedPrompt.containsKey("id")) {
      annotatedPrompt.put("id", mlApp + "_" + DEFAULT_PROMPT_NAME);
    }
    putIfPresent(annotatedPrompt, "version", prompt.getVersion());
    putIfPresent(annotatedPrompt, "variables", prompt.getVariables());
    if (prompt.getTemplate() != null) {
      annotatedPrompt.remove("chat_template");
      annotatedPrompt.put("template", prompt.getTemplate());
    } else if (prompt.getChatTemplate() != null && !prompt.getChatTemplate().isEmpty()) {
      annotatedPrompt.remove("template");
      annotatedPrompt.put("chat_template", toChatTemplate(prompt.getChatTemplate()));
    }
    putIfPresent(annotatedPrompt, "tags", prompt.getTags());
    annotatedPrompt.put(
        CONTEXT_VARIABLE_KEYS,
        prompt.getContextVariables() == null
            ? Collections.singletonList("context")
            : prompt.getContextVariables());
    annotatedPrompt.put(
        QUERY_VARIABLE_KEYS,
        prompt.getQueryVariables() == null
            ? Collections.singletonList("question")
            : prompt.getQueryVariables());

    span.setTag(INPUT_PROMPT, annotatedPrompt);
    span.setTag(PROMPT_TRACKING_INSTRUMENTATION_METHOD, INSTRUMENTATION_METHOD_ANNOTATED);
  }

  @Override
  public void annotateAgentManifest(LLMObs.AgentManifest manifest) {
    if (finished || manifest == null) {
      return;
    }
    if (!Tags.LLMOBS_AGENT_SPAN_KIND.equals(spanKind)) {
      LOGGER.warn(
          "dropping agent manifest on non-agent span kind; annotateAgentManifest is only supported for agent spans");
      return;
    }
    // Read existing manifest (may be null on first call)
    Object existing = span.getTag(AGENT_MANIFEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> base =
        (existing instanceof Map)
            ? new LinkedHashMap<>((Map<String, Object>) existing)
            : new LinkedHashMap<>();
    mergeManifest(base, manifest);
    base.put("framework", MANUAL_FRAMEWORK);
    span.setTag(AGENT_MANIFEST, base);
  }

  private void mergeManifest(Map<String, Object> base, LLMObs.AgentManifest manifest) {
    // name: new non-empty wins, else keep existing, else span name
    String manifestName = manifest.getName();
    if (manifestName != null && !manifestName.isEmpty()) {
      base.put("name", manifestName);
    } else if (!base.containsKey("name")) {
      CharSequence spanName = span.getSpanName();
      if (spanName != null && spanName.length() > 0) {
        base.put("name", spanName.toString());
      }
    }
    // instructions
    String instructions = manifest.getInstructions();
    if (instructions != null && !instructions.isEmpty()) {
      base.put("instructions", instructions);
    }
    // model
    String model = manifest.getModel();
    if (model != null && !model.isEmpty()) {
      base.put("model", model);
    }
    // model_settings: shallow merge
    Map<String, Object> modelSettings = manifest.getModelSettings();
    if (modelSettings != null && !modelSettings.isEmpty()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> existingSettings =
          (base.get("model_settings") instanceof Map)
              ? new LinkedHashMap<>((Map<String, Object>) base.get("model_settings"))
              : new LinkedHashMap<>();
      existingSettings.putAll(modelSettings);
      base.put("model_settings", existingSettings);
    }
    // tools: replace if non-null non-empty
    List<LLMObs.AgentTool> tools = manifest.getTools();
    if (tools != null && !tools.isEmpty()) {
      List<Map<String, Object>> toolList = new ArrayList<>();
      for (LLMObs.AgentTool tool : tools) {
        String toolName = tool == null ? null : tool.getName();
        if (toolName == null || toolName.isEmpty()) {
          LOGGER.warn("agent manifest tool missing required name; skipping");
          continue;
        }
        Map<String, Object> toolMap = new LinkedHashMap<>();
        toolMap.put("name", toolName);
        if (tool.getDescription() != null) {
          toolMap.put("description", tool.getDescription());
        }
        if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
          toolMap.put("parameters", new LinkedHashMap<>(tool.getParameters()));
        }
        toolList.add(toolMap);
      }
      if (!toolList.isEmpty()) {
        base.put("tools", toolList);
      }
    }
  }

  private static Map<String, Object> copyStringKeyedMap(Map<?, ?> source) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (entry.getKey() instanceof String) {
        copy.put((String) entry.getKey(), entry.getValue());
      }
    }
    return copy;
  }

  private static void putIfPresent(Map<String, Object> prompt, String key, Object value) {
    if (value != null) {
      prompt.put(key, value);
    }
  }

  private static List<Map<String, String>> toChatTemplate(List<LLMObs.LLMMessage> messages) {
    List<Map<String, String>> chatTemplate = new ArrayList<>(messages.size());
    for (LLMObs.LLMMessage message : messages) {
      if (message == null || message.getRole() == null || message.getContent() == null) {
        LOGGER.warn("prompt chat template messages must define both role and content; skipping");
        continue;
      }
      Map<String, String> templateMessage = new LinkedHashMap<>();
      templateMessage.put("role", message.getRole());
      templateMessage.put("content", message.getContent());
      chatTemplate.add(templateMessage);
    }
    return chatTemplate;
  }

  @Override
  public void setToolDefinitions(List<LLMObs.ToolDefinition> toolDefinitions) {
    if (finished || toolDefinitions == null || toolDefinitions.isEmpty()) {
      return;
    }
    List<LLMObs.ToolDefinition> validToolDefinitions = new ArrayList<>(toolDefinitions.size());
    for (int i = 0; i < toolDefinitions.size(); i++) {
      LLMObs.ToolDefinition toolDefinition = toolDefinitions.get(i);
      if (toolDefinition == null) {
        LOGGER.warn("tool definition at index {} is null; skipping", i);
        continue;
      }
      if (toolDefinition.getName() == null || toolDefinition.getName().isEmpty()) {
        LOGGER.warn("tool definition at index {} must have a non-empty name; skipping", i);
        continue;
      }
      validToolDefinitions.add(toolDefinition);
    }
    if (!validToolDefinitions.isEmpty()) {
      span.setTag(TOOL_DEFINITIONS, validToolDefinitions);
    }
  }

  @Override
  public void setMetadata(Map<String, Object> metadata) {
    if (finished) {
      return;
    }
    Object value = span.getTag(METADATA);
    if (value == null) {
      span.setTag(METADATA, new HashMap<>(metadata));
      return;
    }

    if (value instanceof Map) {
      Map<String, Object> mergedMetadata = copyStringKeyedMap((Map<?, ?>) value);
      mergedMetadata.putAll(metadata);
      span.setTag(METADATA, mergedMetadata);
    } else {
      LOGGER.debug(
          "unexpected instance type for metadata {}, overwriting for now",
          value.getClass().getName());
      span.setTag(METADATA, new HashMap<>(metadata));
    }
  }

  @Override
  public void setMetrics(Map<String, Number> metrics) {
    if (finished) {
      return;
    }
    for (Map.Entry<String, Number> entry : metrics.entrySet()) {
      span.setMetric(LLMOBS_METRIC_PREFIX + entry.getKey(), entry.getValue().doubleValue());
    }
  }

  @Override
  public void setMetric(CharSequence key, int value) {
    if (finished) {
      return;
    }
    span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, long value) {
    if (finished) {
      return;
    }
    span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, double value) {
    if (finished) {
      return;
    }
    span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setTags(Map<String, Object> tags) {
    if (finished) {
      return;
    }
    if (tags != null && !tags.isEmpty()) {
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        span.setTag(LLMOBS_TAG_PREFIX + entry.getKey(), entry.getValue());
      }
    }
  }

  @Override
  public void setTag(String key, String value) {
    if (finished) {
      return;
    }
    span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, boolean value) {
    if (finished) {
      return;
    }
    span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, int value) {
    if (finished) {
      return;
    }
    span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, long value) {
    if (finished) {
      return;
    }
    span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, double value) {
    if (finished) {
      return;
    }
    span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setError(boolean error) {
    if (finished) {
      return;
    }
    span.setError(error);
  }

  @Override
  public void setErrorMessage(String errorMessage) {
    if (finished) {
      return;
    }
    if (errorMessage == null || errorMessage.isEmpty()) {
      return;
    }
    span.setError(true);
    span.setErrorMessage(errorMessage);
  }

  @Override
  public void addThrowable(Throwable throwable) {
    if (finished) {
      return;
    }
    if (throwable == null) {
      return;
    }
    span.setError(true);
    span.addThrowable(throwable);
  }

  @Override
  public void finish() {
    if (finished) {
      return;
    }
    span.finish();
    scope.close();
    finished = true;
    boolean isRootSpan = span.getLocalRootSpan() == span;
    LLMObsMetricCollector.get()
        .recordSpanFinished(
            LLM_OBS_INSTRUMENTATION_NAME,
            spanKind,
            isRootSpan,
            false,
            span.isError(),
            hasSessionId);
  }

  @Override
  public DDTraceId getTraceId() {
    return span.getTraceId();
  }

  @Override
  public long getSpanId() {
    return span.getSpanId();
  }
}

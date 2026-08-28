package datadog.trace.llmobs.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;
import static java.util.concurrent.TimeUnit.MINUTES;

import datadog.communication.EvpProxy;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.DDTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.api.llmobs.LLMObsSpanData;
import datadog.trace.api.llmobs.LLMObsSpanProcessor;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMObsSpanMapper implements RemoteMapper {

  private static final boolean[] NO_DROPPED_SPANS = new boolean[0];

  // Well known tags for LLM obs will be prefixed with _ml_obs_(tags|metrics).
  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  // internal tags to be prefixed
  private static final String INPUT = "input";
  private static final String INPUT_PROMPT = "input_prompt";
  private static final String OUTPUT = "output";
  private static final String SPAN_KIND_TAG_KEY = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;

  private static final Logger LOGGER = LoggerFactory.getLogger(LLMObsSpanMapper.class);
  private static final RatelimitedLogger PROCESSOR_ERROR_LOGGER =
      new RatelimitedLogger(LOGGER, 1, MINUTES);

  private static final byte[] STAGE = "_dd.stage".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EVENT_TYPE = "event_type".getBytes(StandardCharsets.UTF_8);

  private static final byte[] SPAN_ID = "span_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TRACE_ID = "trace_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] DD = "_dd".getBytes(StandardCharsets.UTF_8);
  private static final byte[] APM_TRACE_ID = "apm_trace_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PARENT_ID = "parent_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SESSION_ID = "session_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NAME = "name".getBytes(StandardCharsets.UTF_8);
  private static final byte[] DURATION = "duration".getBytes(StandardCharsets.UTF_8);
  private static final byte[] START_NS = "start_ns".getBytes(StandardCharsets.UTF_8);
  private static final byte[] STATUS = "status".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ERROR = "error".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ERROR_MESSAGE = "message".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ERROR_TYPE = "type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ERROR_STACK = "stack".getBytes(StandardCharsets.UTF_8);

  private static final byte[] META = "meta".getBytes(StandardCharsets.UTF_8);
  private static final byte[] AGENT_ATTRIBUTION =
      "agent_attribution".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PAGENT_NAME = "pagent_name".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PAGENT_SPAN_ID = "pagent_span_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METADATA = "metadata".getBytes(StandardCharsets.UTF_8);
  private static final byte[] AGENT_MANIFEST_KEY =
      "agent_manifest".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PROMPT = "prompt".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPAN_KIND = "span.kind".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPANS = "spans".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METRICS = "metrics".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TAGS = "tags".getBytes(StandardCharsets.UTF_8);
  private static final String LLMOBS_LANGUAGE_TAG = "language:jvm";

  private static final byte[] LLM_MESSAGE_ROLE = "role".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_MESSAGE_CONTENT = "content".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_MESSAGE_TOOL_CALLS =
      "tool_calls".getBytes(StandardCharsets.UTF_8);

  private static final byte[] LLM_TOOL_CALL_NAME = "name".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_TYPE = "type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_TOOL_ID = "tool_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_ARGUMENTS =
      "arguments".getBytes(StandardCharsets.UTF_8);

  private static final byte[] LLM_MESSAGE_TOOL_RESULTS =
      "tool_results".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_RESULT_RESULT = "result".getBytes(StandardCharsets.UTF_8);

  private static final String PARENT_ID_TAG_INTERNAL_FULL = LLMOBS_TAG_PREFIX + "parent_id";
  private static final String SESSION_ID_TAG_INTERNAL_FULL =
      LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID;
  private static final String PAGENT_SPAN_ID_TAG_INTERNAL_FULL =
      LLMOBS_TAG_PREFIX + "pagent_span_id";
  private static final String PAGENT_NAME_TAG_INTERNAL_FULL = LLMOBS_TAG_PREFIX + "pagent_name";

  private final MetaWriter metaWriter = new MetaWriter();
  private final int size;

  private final ByteBuffer header;
  private boolean[] pendingDroppedSpans;
  private int spansWritten;

  public LLMObsSpanMapper() {
    this(EvpProxy.PAYLOAD_SIZE_LIMIT_BYTES);
  }

  private LLMObsSpanMapper(int size) {
    this.size = size;

    GrowableBuffer header = new GrowableBuffer(64);
    MsgPackWriter headerWriter = new MsgPackWriter(header);

    headerWriter.startMap(3);
    headerWriter.writeUTF8(EVENT_TYPE);
    headerWriter.writeString("span", null);
    headerWriter.writeUTF8(STAGE);
    headerWriter.writeString("raw", null);
    headerWriter.writeUTF8(SPANS);

    this.header = header.slice();
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    this.map(trace, writable, false);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable, boolean retry) {
    if (!retry) {
      pendingDroppedSpans = null;
    }

    List<? extends CoreSpan<?>> llmobsSpans =
        trace.stream().filter(LLMObsSpanMapper::isLLMObsSpan).collect(Collectors.toList());

    if (llmobsSpans.isEmpty()) {
      // do nothing if no llmobs spans in the trace
      return;
    }

    llmobsSpans = processSpans(llmobsSpans, retry);

    for (CoreSpan<?> span : llmobsSpans) {
      // Read session_id off the span before opening the map so we can size it correctly.
      // We deliberately do NOT remove the tag (unlike parent_id) — the session_id:<value>
      // entry must remain in the tags[] array to match dd-trace-py and dd-trace-js behavior.
      // span.getTag returns Object — guard against generic tag APIs setting a non-string
      // session_id value, which would otherwise throw ClassCastException here and drop
      // the entire LLMObs payload for the trace.
      Object rawSessionId = span.getTag(SESSION_ID_TAG_INTERNAL_FULL);
      String sessionId = rawSessionId instanceof String ? (String) rawSessionId : null;
      boolean hasSessionId = sessionId != null && !sessionId.isEmpty();

      writable.startMap(hasSessionId ? 12 : 11);
      // 1
      writable.writeUTF8(SPAN_ID);
      writable.writeString(String.valueOf(span.getSpanId()), null);

      // 2
      writable.writeUTF8(TRACE_ID);
      writable.writeString(span.getTraceId().toHexString(), null);

      // 3
      writable.writeUTF8(PARENT_ID);
      writable.writeString(span.getTag(PARENT_ID_TAG_INTERNAL_FULL), null);
      span.removeTag(PARENT_ID_TAG_INTERNAL_FULL);

      // 4
      writable.writeUTF8(NAME);
      writable.writeString(llmObsSpanName(span), null);

      // 5
      writable.writeUTF8(START_NS);
      writable.writeUnsignedLong(span.getStartTime());

      // 6
      writable.writeUTF8(DURATION);
      writable.writeFloat(span.getDurationNano());

      // 7
      writable.writeUTF8(STATUS);
      writable.writeString(span.getError() == 0 ? "ok" : "error", null);

      // 8
      writable.writeUTF8(DD);
      writable.startMap(3);
      writable.writeUTF8(SPAN_ID);
      writable.writeString(String.valueOf(span.getSpanId()), null);
      writable.writeUTF8(TRACE_ID);
      writable.writeString(span.getTraceId().toHexString(), null);
      writable.writeUTF8(APM_TRACE_ID);
      writable.writeString(span.getTraceId().toHexString(), null);

      // 9 — optional top-level session_id field. Required by the LLMObs HTTP intake schema
      // and by the LLM Trace Explorer's Sessions filter, which keys off this field.
      if (hasSessionId) {
        writable.writeUTF8(SESSION_ID);
        writable.writeString(sessionId, null);
      }

      /* 10 (metrics), 11 (tags), 12 meta — shift down 1 if session_id absent */
      span.processTagsAndBaggage(metaWriter.withWritable(writable, getErrorsMap(span)));
    }

    // Increase only after all spans have been written. This way, if it rolls back because of a
    // buffer overflow, the counter won't be skewed.
    spansWritten += llmobsSpans.size();
    pendingDroppedSpans = null;
  }

  private List<? extends CoreSpan<?>> processSpans(
      List<? extends CoreSpan<?>> spans, boolean retry) {
    if (retry && pendingDroppedSpans != null) {
      boolean[] droppedSpans = pendingDroppedSpans;
      pendingDroppedSpans = null;
      if (droppedSpans.length == 0) {
        return spans;
      }

      List<CoreSpan<?>> processedSpans = new ArrayList<>(spans.size());
      for (int i = 0; i < spans.size(); i++) {
        if (!droppedSpans[i]) {
          processedSpans.add(spans.get(i));
        }
      }
      return processedSpans;
    }

    LLMObsSpanProcessor processor = LLMObsInternal.getSpanProcessor();
    if (processor == null) {
      return spans;
    }

    pendingDroppedSpans = NO_DROPPED_SPANS;
    List<CoreSpan<?>> processedSpans = new ArrayList<>(spans.size());
    for (int i = 0; i < spans.size(); i++) {
      CoreSpan<?> span = spans.get(i);
      boolean processorError = false;
      try {
        LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
        LLMObsSpanData result = processor.process(adapter);
        if (result != null) {
          adapter.apply(result);
          processedSpans.add(span);
        } else {
          markDroppedSpan(i, spans.size());
        }
      } catch (Throwable error) {
        processorError = true;
        markDroppedSpan(i, spans.size());
        PROCESSOR_ERROR_LOGGER.warn(
            "Error in LLM Observability span processor, dropping span", error);
      } finally {
        LLMObsMetricCollector.get().recordUserProcessorCalled(processorError);
      }
    }
    return processedSpans;
  }

  private void markDroppedSpan(int index, int spanCount) {
    if (pendingDroppedSpans.length == 0) {
      pendingDroppedSpans = new boolean[spanCount];
    }
    pendingDroppedSpans[index] = true;
  }

  private CharSequence llmObsSpanName(CoreSpan<?> span) {
    CharSequence operationName = span.getOperationName();
    if ("openai.request".contentEquals(operationName)) {
      return "OpenAI." + span.getResourceName();
    }
    return operationName;
  }

  private static boolean isLLMObsSpan(CoreSpan<?> span) {
    CharSequence type = span.getType();
    return type != null && type.toString().contentEquals(InternalSpanTypes.LLMOBS);
  }

  @Override
  public Payload newPayload() {
    return new PayloadV1(header, spansWritten);
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {
    // Reset the number of spans per message with each flush.
    spansWritten = 0;
  }

  @Override
  public String endpoint() {
    return TrackType.LLMOBS + "/v2";
  }

  private static Map<String, String> getErrorsMap(CoreSpan<?> span) {
    Map<String, String> errors = new HashMap<>();
    String errorMsg = span.getTag(DDTags.ERROR_MSG);
    if (errorMsg != null && !errorMsg.isEmpty()) {
      errors.put("message", errorMsg);
    }
    String errorType = span.getTag(DDTags.ERROR_TYPE);
    if (errorType != null && !errorType.isEmpty()) {
      errors.put("type", errorType);
    }
    String errorStack = span.getTag(DDTags.ERROR_STACK);
    if (errorStack != null && !errorStack.isEmpty()) {
      errors.put("stack", errorStack);
    }
    return errors;
  }

  private static final class MetaWriter implements MetadataConsumer {

    private Writable writable;
    private Map<String, String> errorInfo;

    private static final Set<String> TAGS_FOR_REMAPPING =
        Collections.unmodifiableSet(
            new HashSet<>(
                Arrays.asList(
                    LLMOBS_TAG_PREFIX + INPUT,
                    LLMOBS_TAG_PREFIX + INPUT_PROMPT,
                    LLMOBS_TAG_PREFIX + OUTPUT,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_NAME,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_PROVIDER,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_VERSION,
                    LLMOBS_TAG_PREFIX + LLMObsTags.TOOL_DEFINITIONS,
                    LLMOBS_TAG_PREFIX + LLMObsTags.METADATA,
                    LLMOBS_TAG_PREFIX + LLMObsTags.AGENT_MANIFEST,
                    PAGENT_SPAN_ID_TAG_INTERNAL_FULL,
                    PAGENT_NAME_TAG_INTERNAL_FULL)));

    MetaWriter withWritable(Writable writable, Map<String, String> errorInfo) {
      this.writable = writable;
      this.errorInfo = errorInfo;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      Map<String, Object> tagsToRemapToMeta = new HashMap<>();
      int metricsSize = 0, tagsSize = 0;
      String spanKind = "unknown";
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        String key = tag.getKey();
        if (key.equals(SPAN_KIND_TAG_KEY)) {
          spanKind = String.valueOf(tag.getValue());
        } else if (TAGS_FOR_REMAPPING.contains(key)) {
          tagsToRemapToMeta.put(key, tag.getValue());
        } else if (key.startsWith(LLMOBS_METRIC_PREFIX) && tag.getValue() instanceof Number) {
          ++metricsSize;
        } else if (key.startsWith(LLMOBS_TAG_PREFIX)) {
          if (key.startsWith(LLMOBS_TAG_PREFIX)) {
            key = key.substring(LLMOBS_TAG_PREFIX.length());
          }
          if (TAGS_FOR_REMAPPING.contains(key)) {
            tagsToRemapToMeta.put(key, tag.getValue());
          } else {
            ++tagsSize;
          }
        }
      }

      if (!spanKind.equals("unknown")) {
        metadata.getTags().remove(SPAN_KIND_TAG_KEY);
      } else {
        LOGGER.warn("missing span kind");
      }

      String inputTag = LLMOBS_TAG_PREFIX + INPUT;
      String inputPromptTag = LLMOBS_TAG_PREFIX + INPUT_PROMPT;
      boolean hasInput = tagsToRemapToMeta.containsKey(inputTag);
      boolean hasInputPrompt = tagsToRemapToMeta.containsKey(inputPromptTag);
      Object pagentSpanIdVal = tagsToRemapToMeta.get(PAGENT_SPAN_ID_TAG_INTERNAL_FULL);
      boolean hasAgentAttribution =
          pagentSpanIdVal instanceof String && !((String) pagentSpanIdVal).isEmpty();
      boolean hasAgentAttributionName =
          tagsToRemapToMeta.containsKey(PAGENT_NAME_TAG_INTERNAL_FULL);
      Object inputPrompt = null;
      if (hasInputPrompt) {
        if (spanKind.equals(Tags.LLMOBS_LLM_SPAN_KIND)) {
          inputPrompt = tagsToRemapToMeta.get(inputPromptTag);
        } else {
          LOGGER.warn(
              "dropping prompt on non-LLM span kind, annotating prompts is only supported for LLM span kinds");
        }
      }

      // write metrics (9)
      writable.writeUTF8(METRICS);
      writable.startMap(metricsSize);
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        String tagKey = tag.getKey();
        if (tagKey.startsWith(LLMOBS_METRIC_PREFIX) && tag.getValue() instanceof Number) {
          writable.writeString(tagKey.substring(LLMOBS_METRIC_PREFIX.length()), null);
          writable.writeObject(tag.getValue(), null);
        }
      }

      // write tags (10)
      writable.writeUTF8(TAGS);
      writable.startArray(tagsSize + 1);
      writable.writeString(LLMOBS_LANGUAGE_TAG, null);
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        String key = tag.getKey();
        Object value = tag.getValue();
        if (!tagsToRemapToMeta.containsKey(key) && key.startsWith(LLMOBS_TAG_PREFIX)) {
          writable.writeObject(key.substring(LLMOBS_TAG_PREFIX.length()) + ":" + value, null);
        }
      }

      // write meta (11)
      // pagent_name is always emitted inside agent_attribution (never standalone), so subtract 1
      // whenever it is in the map regardless of whether pagent_span_id is also present.
      // When pagent_span_id exists but is invalid (non-string or empty), the whole
      // agent_attribution block is skipped; subtract 1 for that entry too.
      boolean hasInvalidPagentSpanId =
          tagsToRemapToMeta.containsKey(PAGENT_SPAN_ID_TAG_INTERNAL_FULL) && !hasAgentAttribution;
      int metaSize =
          tagsToRemapToMeta.size()
              - (hasInputPrompt ? 1 : 0)
              + (inputPrompt != null && !hasInput ? 1 : 0)
              + 1
              + (null != errorInfo && !errorInfo.isEmpty() ? 1 : 0)
              - (hasAgentAttributionName ? 1 : 0)
              - (hasInvalidPagentSpanId ? 1 : 0);
      writable.writeUTF8(META);
      writable.startMap(metaSize);
      writable.writeUTF8(SPAN_KIND);
      writable.writeString(spanKind, null);

      if (null != errorInfo && !errorInfo.isEmpty()) {
        writable.writeUTF8(ERROR);
        writable.startMap(errorInfo.size());
        for (Map.Entry<String, String> error : errorInfo.entrySet()) {
          switch (error.getKey()) {
            case "message":
              writable.writeUTF8(ERROR_MESSAGE);
              break;
            case "type":
              writable.writeUTF8(ERROR_TYPE);
              break;
            case "stack":
              writable.writeUTF8(ERROR_STACK);
              break;
            default:
              writable.writeString(error.getKey(), null);
              break;
          }
          writable.writeString(error.getValue(), null);
        }
      }

      for (Map.Entry<String, Object> tag : tagsToRemapToMeta.entrySet()) {
        String key = tag.getKey().substring(LLMOBS_TAG_PREFIX.length());
        Object val = tag.getValue();
        if (key.equals("pagent_name")) {
          // Emitted inside the agent_attribution block below; skip standalone entry.
          continue;
        } else if (key.equals("pagent_span_id")) {
          if (!hasAgentAttribution) {
            // Value was invalid (non-string or empty); skip — subtracted from metaSize above.
            continue;
          }
          // Emit the structured agent_attribution map.
          writable.writeUTF8(AGENT_ATTRIBUTION);
          writable.startMap(2);
          writable.writeUTF8(PAGENT_NAME);
          Object nameVal = tagsToRemapToMeta.get(PAGENT_NAME_TAG_INTERNAL_FULL);
          if (nameVal instanceof String) {
            writable.writeString((String) nameVal, null);
          } else {
            writable.writeNull();
          }
          writable.writeUTF8(PAGENT_SPAN_ID);
          writable.writeString((String) pagentSpanIdVal, null);
          continue;
        } else if (key.equals(INPUT) || key.equals(OUTPUT)) {
          boolean isDocumentIO =
              (spanKind.equals(Tags.LLMOBS_EMBEDDING_SPAN_KIND) && key.equals(INPUT))
                  || (spanKind.equals(Tags.LLMOBS_RETRIEVAL_SPAN_KIND) && key.equals(OUTPUT));
          if (spanKind.equals(Tags.LLMOBS_LLM_SPAN_KIND)) {
            writable.writeString(key, null);
            if (val instanceof List) {
              writeLlmMessagesField((List<?>) val, key.equals(INPUT) ? inputPrompt : null);
            } else if (key.equals(INPUT) && val instanceof Map) {
              writeLlmInputMap((Map<?, ?>) val, inputPrompt);
            } else {
              LOGGER.warn(
                  "unexpectedly found incorrect type for LLM span IO {}, expecting list",
                  val.getClass().getName());
              continue;
            }
          } else if (isDocumentIO && isDocumentList(val)) {
            writable.writeString(key, null);
            writable.startMap(1);
            List<LLMObs.Document> documents = (List<LLMObs.Document>) val;
            writable.writeString("documents", null);
            writable.startArray(documents.size());
            for (LLMObs.Document document : documents) {
              int documentSize = 1;
              if (document.getName() != null) documentSize++;
              if (document.getId() != null) documentSize++;
              if (document.getScore() != null) documentSize++;
              writable.startMap(documentSize);
              writable.writeString("text", null);
              writable.writeString(document.getText(), null);
              if (document.getName() != null) {
                writable.writeString("name", null);
                writable.writeString(document.getName(), null);
              }
              if (document.getId() != null) {
                writable.writeString("id", null);
                writable.writeString(document.getId(), null);
              }
              if (document.getScore() != null) {
                writable.writeString("score", null);
                writable.writeObject(document.getScore(), null);
              }
            }
          } else {
            if (isDocumentIO) {
              LOGGER.warn(
                  "unexpectedly found invalid document data for {} span {}, serializing as value",
                  spanKind,
                  key);
            }
            writable.writeString(key, null);
            writable.startMap(1);
            writable.writeString("value", null);
            writable.writeObject(val, null);
          }
        } else if (key.equals(INPUT_PROMPT)) {
          // Serialized as meta.input.prompt above, or after this loop when no input is present.
          continue;
        } else if (key.equals(LLMObsTags.TOOL_DEFINITIONS) && val instanceof List) {
          writable.writeString(key, null);
          writeToolDefinitions((List<?>) val);
        } else if (key.equals(LLMObsTags.METADATA) && val instanceof Map) {
          Map<String, Object> metadataMap = (Map) val;
          writable.writeUTF8(METADATA);
          writable.startMap(metadataMap.size());
          for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
            writable.writeString(entry.getKey(), null);
            writable.writeObject(entry.getValue(), null);
          }
        } else if (key.equals(LLMObsTags.AGENT_MANIFEST) && val instanceof Map) {
          Map<?, ?> manifestMap = (Map<?, ?>) val;
          writable.writeUTF8(AGENT_MANIFEST_KEY);
          writable.startMap(manifestMap.size());
          for (Map.Entry<?, ?> entry : manifestMap.entrySet()) {
            writable.writeString(String.valueOf(entry.getKey()), null);
            writable.writeObject(entry.getValue(), null);
          }
        } else {
          writable.writeString(key, null);
          writable.writeObject(val, null);
        }
      }

      if (inputPrompt != null && !hasInput) {
        writable.writeString(INPUT, null);
        writable.startMap(1);
        writable.writeUTF8(PROMPT);
        writable.writeObject(inputPrompt, null);
      }
    }

    private void writeToolDefinitions(List<?> toolDefinitions) {
      writable.startArray(toolDefinitions.size());
      for (Object toolDefinitionObject : toolDefinitions) {
        if (!(toolDefinitionObject instanceof LLMObs.ToolDefinition)) {
          writable.writeObject(toolDefinitionObject, null);
          continue;
        }

        LLMObs.ToolDefinition toolDefinition = (LLMObs.ToolDefinition) toolDefinitionObject;
        int mapSize = 1;
        if (toolDefinition.getDescription() != null) mapSize++;
        if (toolDefinition.getSchema() != null) mapSize++;
        if (toolDefinition.getVersion() != null) mapSize++;

        writable.startMap(mapSize);
        writable.writeString("name", null);
        writable.writeString(toolDefinition.getName(), null);
        if (toolDefinition.getDescription() != null) {
          writable.writeString("description", null);
          writable.writeString(toolDefinition.getDescription(), null);
        }
        if (toolDefinition.getSchema() != null) {
          writable.writeString("schema", null);
          writable.writeObject(toolDefinition.getSchema(), null);
        }
        if (toolDefinition.getVersion() != null) {
          writable.writeString("version", null);
          writable.writeString(toolDefinition.getVersion(), null);
        }
      }
    }

    private static boolean isDocumentList(Object value) {
      if (!(value instanceof List)) {
        return false;
      }
      for (Object item : (List<?>) value) {
        if (!(item instanceof LLMObs.Document)) {
          return false;
        }
      }
      return true;
    }

    private void writeLlmMessagesField(List<?> messages, Object inputPrompt) {
      writable.startMap(inputPrompt == null ? 1 : 2);
      writable.writeString("messages", null);
      writeLlmMessages(messages);
      if (inputPrompt != null) {
        writable.writeUTF8(PROMPT);
        writable.writeObject(inputPrompt, null);
      }
    }

    private void writeLlmInputMap(Map<?, ?> inputMap, Object inputPrompt) {
      boolean addInputPrompt = inputPrompt != null && !inputMap.containsKey("prompt");
      writable.startMap(inputMap.size() + (addInputPrompt ? 1 : 0));
      for (Map.Entry<?, ?> entry : inputMap.entrySet()) {
        String inputKey = String.valueOf(entry.getKey());
        Object inputValue = entry.getValue();
        writable.writeString(inputKey, null);
        if ("messages".equals(inputKey) && inputValue instanceof List) {
          writeLlmMessages((List<?>) inputValue);
        } else {
          writable.writeObject(inputValue, null);
        }
      }
      if (addInputPrompt) {
        writable.writeUTF8(PROMPT);
        writable.writeObject(inputPrompt, null);
      }
    }

    private void writeLlmMessages(List<?> messages) {
      writable.startArray(messages.size());
      for (Object messageObj : messages) {
        if (!(messageObj instanceof LLMObs.LLMMessage)) {
          writable.writeObject(messageObj, null);
          continue;
        }

        LLMObs.LLMMessage message = (LLMObs.LLMMessage) messageObj;
        List<LLMObs.ToolCall> toolCalls = message.getToolCalls();
        List<LLMObs.ToolResult> toolResults = message.getToolResults();
        boolean hasToolCalls = null != toolCalls && !toolCalls.isEmpty();
        boolean hasToolResults = null != toolResults && !toolResults.isEmpty();
        boolean hasContent = message.getContent() != null;
        int mapSize = 1;
        if (hasContent) mapSize++;
        if (hasToolCalls) mapSize++;
        if (hasToolResults) mapSize++;
        writable.startMap(mapSize);
        writable.writeUTF8(LLM_MESSAGE_ROLE);
        writable.writeString(message.getRole(), null);
        if (hasContent) {
          writable.writeUTF8(LLM_MESSAGE_CONTENT);
          writable.writeString(message.getContent(), null);
        }
        if (hasToolCalls) {
          writable.writeUTF8(LLM_MESSAGE_TOOL_CALLS);
          writable.startArray(toolCalls.size());
          for (LLMObs.ToolCall toolCall : toolCalls) {
            Map<String, Object> arguments = toolCall.getArguments();
            boolean hasArguments = null != arguments && !arguments.isEmpty();
            writable.startMap(hasArguments ? 4 : 3);
            writable.writeUTF8(LLM_TOOL_CALL_NAME);
            writable.writeString(toolCall.getName(), null);
            writable.writeUTF8(LLM_TOOL_CALL_TYPE);
            writable.writeString(toolCall.getType(), null);
            writable.writeUTF8(LLM_TOOL_CALL_TOOL_ID);
            writable.writeString(toolCall.getToolId(), null);
            if (hasArguments) {
              writable.writeUTF8(LLM_TOOL_CALL_ARGUMENTS);
              writable.startMap(arguments.size());
              for (Map.Entry<String, Object> argument : arguments.entrySet()) {
                writable.writeString(argument.getKey(), null);
                writable.writeObject(argument.getValue(), null);
              }
            }
          }
        }
        if (hasToolResults) {
          writable.writeUTF8(LLM_MESSAGE_TOOL_RESULTS);
          writable.startArray(toolResults.size());
          for (LLMObs.ToolResult toolResult : toolResults) {
            writable.startMap(4);
            writable.writeUTF8(LLM_TOOL_CALL_NAME);
            writable.writeString(toolResult.getName(), null);
            writable.writeUTF8(LLM_TOOL_CALL_TYPE);
            writable.writeString(toolResult.getType(), null);
            writable.writeUTF8(LLM_TOOL_CALL_TOOL_ID);
            writable.writeString(toolResult.getToolId(), null);
            writable.writeUTF8(LLM_TOOL_RESULT_RESULT);
            writable.writeString(toolResult.getResult(), null);
          }
        }
      }
    }
  }

  private static class PayloadV1 extends Payload {
    private final ByteBuffer header;
    private final int spansWritten;

    public PayloadV1(ByteBuffer header, int spansWritten) {
      this.spansWritten = spansWritten;
      this.header = header;
    }

    @Override
    public int sizeInBytes() {
      if (traceCount() == 0) {
        return msgpackMapHeaderSize(0);
      }
      return header.remaining() + msgpackArrayHeaderSize(spansWritten) + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      // If traceCount is 0, we write a map with 0 elements in MsgPack format.
      if (traceCount() == 0) {
        ByteBuffer emptyDict = msgpackMapHeader(0);
        while (emptyDict.hasRemaining()) {
          channel.write(emptyDict);
        }
      } else {
        while (body.hasRemaining()) {
          channel.write(header.slice());
          channel.write(msgpackArrayHeader(spansWritten));
          channel.write(body);
        }
      }
    }

    @Override
    public RequestBody toRequest() {
      List<ByteBuffer> buffers;
      if (traceCount() == 0) {
        buffers = Collections.singletonList(msgpackMapHeader(0));
      } else {
        buffers =
            Arrays.asList(
                header.slice(),
                // Third Value: is an array of spans serialized into the body
                msgpackArrayHeader(spansWritten),
                body);
      }
      return gzippedMsgpackRequestBodyOf(buffers);
    }
  }
}

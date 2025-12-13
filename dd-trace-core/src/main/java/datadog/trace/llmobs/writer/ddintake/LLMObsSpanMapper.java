package datadog.trace.llmobs.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;

import datadog.communication.serialization.Writable;
import datadog.trace.api.DDTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsTags;
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

  // Well known tags for LLM obs will be prefixed with _ml_obs_(tags|metrics).
  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  // internal tags to be prefixed
  private static final String INPUT = "input";
  private static final String OUTPUT = "output";
  private static final String SPAN_KIND_TAG_KEY = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;

  private static final Logger LOGGER = LoggerFactory.getLogger(LLMObsSpanMapper.class);

  private static final byte[] STAGE = "_dd.stage".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EVENT_TYPE = "event_type".getBytes(StandardCharsets.UTF_8);

  private static final byte[] SPAN_ID = "span_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TRACE_ID = "trace_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PARENT_ID = "parent_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NAME = "name".getBytes(StandardCharsets.UTF_8);
  private static final byte[] DURATION = "duration".getBytes(StandardCharsets.UTF_8);
  private static final byte[] START_NS = "start_ns".getBytes(StandardCharsets.UTF_8);
  private static final byte[] STATUS = "status".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ERROR = "error".getBytes(StandardCharsets.UTF_8);

  private static final byte[] META = "meta".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METADATA = "metadata".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPAN_KIND = "span.kind".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPANS = "spans".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METRICS = "metrics".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TAGS = "tags".getBytes(StandardCharsets.UTF_8);

  private static final byte[] LLM_MESSAGE_ROLE = "role".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_MESSAGE_CONTENT = "content".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_MESSAGE_TOOL_CALLS =
      "tool_calls".getBytes(StandardCharsets.UTF_8);

  private static final byte[] LLM_TOOL_CALL_NAME = "name".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_TYPE = "type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_TOOL_ID = "tool_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LLM_TOOL_CALL_ARGUMENTS =
      "arguments".getBytes(StandardCharsets.UTF_8);

  private static final String PARENT_ID_TAG_INTERNAL_FULL = LLMOBS_TAG_PREFIX + "parent_id";

  private final LLMObsSpanMapper.MetaWriter metaWriter = new MetaWriter();
  private final int size;

  public LLMObsSpanMapper() {
    this(5 << 20);
  }

  private LLMObsSpanMapper(int size) {
    this.size = size;
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    List<? extends CoreSpan<?>> llmobsSpans =
        trace.stream().filter(LLMObsSpanMapper::isLLMObsSpan).collect(Collectors.toList());

    writable.startMap(3);

    writable.writeUTF8(EVENT_TYPE);
    writable.writeString("span", null);

    writable.writeUTF8(STAGE);
    writable.writeString("raw", null);

    writable.writeUTF8(SPANS);
    writable.startArray(llmobsSpans.size());
    for (CoreSpan<?> span : llmobsSpans) {
      writable.startMap(11);
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
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());

      boolean errored = span.getError() == 1;

      // 8
      writable.writeUTF8(STATUS);
      writable.writeString(errored ? "error" : "ok", null);

      /* 9 (metrics), 10 (tags), 11 meta */
      span.processTagsAndBaggage(metaWriter.withWritable(writable, getErrorsMap(span)));
    }
  }

  private CharSequence llmObsSpanName(CoreSpan<?> span) {
    CharSequence operationName = span.getOperationName();
    CharSequence resourceName = span.getResourceName();
    if ("openai.request".contentEquals(operationName)) {
      return "OpenAI." + resourceName;
    }
    return operationName;
  }

  private static boolean isLLMObsSpan(CoreSpan<?> span) {
    CharSequence type = span.getType();
    return type != null && type.toString().contentEquals(InternalSpanTypes.LLMOBS);
  }

  @Override
  public Payload newPayload() {
    return new PayloadV1();
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {}

  @Override
  public String endpoint() {
    return TrackType.LLMOBS + "/v2";
  }

  private static Map<String, String> getErrorsMap(CoreSpan<?> span) {
    Map<String, String> errors = new HashMap<>();
    String errorMsg = span.getTag(DDTags.ERROR_MSG);
    if (errorMsg != null && !errorMsg.isEmpty()) {
      errors.put(DDTags.ERROR_MSG, errorMsg);
    }
    String errorType = span.getTag(DDTags.ERROR_TYPE);
    if (errorType != null && !errorType.isEmpty()) {
      errors.put(DDTags.ERROR_TYPE, errorType);
    }
    String errorStack = span.getTag(DDTags.ERROR_STACK);
    if (errorStack != null && !errorStack.isEmpty()) {
      errors.put(DDTags.ERROR_STACK, errorStack);
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
                    LLMOBS_TAG_PREFIX + OUTPUT,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_NAME,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_PROVIDER,
                    LLMOBS_TAG_PREFIX + LLMObsTags.MODEL_VERSION,
                    LLMOBS_TAG_PREFIX + LLMObsTags.METADATA)));

    LLMObsSpanMapper.MetaWriter withWritable(Writable writable, Map<String, String> errorInfo) {
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
      writable.writeString("language:jvm", null);
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        String key = tag.getKey();
        Object value = tag.getValue();
        if (!tagsToRemapToMeta.containsKey(key) && key.startsWith(LLMOBS_TAG_PREFIX)) {
          writable.writeObject(key.substring(LLMOBS_TAG_PREFIX.length()) + ":" + value, null);
        }
      }

      // write meta (11)
      int metaSize = tagsToRemapToMeta.size() + 1 + (null != errorInfo ? errorInfo.size() : 0);
      writable.writeUTF8(META);
      writable.startMap(metaSize);
      writable.writeUTF8(SPAN_KIND);
      writable.writeString(spanKind, null);

      for (Map.Entry<String, String> error : errorInfo.entrySet()) {
        writable.writeUTF8(error.getKey().getBytes());
        writable.writeString(error.getValue(), null);
      }

      for (Map.Entry<String, Object> tag : tagsToRemapToMeta.entrySet()) {
        String key = tag.getKey().substring(LLMOBS_TAG_PREFIX.length());
        Object val = tag.getValue();
        if (key.equals(INPUT) || key.equals(OUTPUT)) {
          writable.writeString(key, null);
          writable.startMap(1);
          if (spanKind.equals(Tags.LLMOBS_LLM_SPAN_KIND)) {
            if (!(val instanceof List)) {
              LOGGER.warn(
                  "unexpectedly found incorrect type for LLM span IO {}, expecting list",
                  val.getClass().getName());
              continue;
            }
            // llm span kind must have llm objects
            List<LLMObs.LLMMessage> messages = (List<LLMObs.LLMMessage>) val;
            writable.writeString("messages", null);
            writable.startArray(messages.size());
            for (LLMObs.LLMMessage message : messages) {
              List<LLMObs.ToolCall> toolCalls = message.getToolCalls();
              boolean hasToolCalls = null != toolCalls && !toolCalls.isEmpty();
              writable.startMap(hasToolCalls ? 3 : 2);
              writable.writeUTF8(LLM_MESSAGE_ROLE);
              writable.writeString(message.getRole(), null);
              writable.writeUTF8(LLM_MESSAGE_CONTENT);
              writable.writeString(message.getContent(), null);
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
            }
          } else if (spanKind.equals(Tags.LLMOBS_EMBEDDING_SPAN_KIND) && key.equals(INPUT)) {
            if (!(val instanceof List)) {
              LOGGER.warn(
                  "unexpectedly found incorrect type for embedding span input {}, expecting list",
                  val.getClass().getName());
              continue;
            }
            List<LLMObs.Document> documents = (List<LLMObs.Document>) val;
            writable.writeString("documents", null);
            writable.startArray(documents.size());
            for (LLMObs.Document document : documents) {
              writable.startMap(1);
              writable.writeString("text", null);
              writable.writeString(document.getText(), null);
            }
          } else {
            writable.writeString("value", null);
            writable.writeObject(val, null);
          }
        } else if (key.equals(LLMObsTags.METADATA) && val instanceof Map) {
          Map<String, Object> metadataMap = (Map) val;
          writable.writeUTF8(METADATA);
          writable.startMap(metadataMap.size());
          for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
            writable.writeString(entry.getKey(), null);
            writable.writeObject(entry.getValue(), null);
          }
        } else {
          writable.writeString(key, null);
          writable.writeObject(val, null);
        }
      }
    }
  }

  private static class PayloadV1 extends Payload {

    @Override
    public int sizeInBytes() {
      if (traceCount() == 0) {
        return msgpackMapHeaderSize(0);
      }

      return body.array().length;
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
        buffers = Collections.singletonList(body);
      }

      return gzippedMsgpackRequestBodyOf(buffers);
    }
  }
}

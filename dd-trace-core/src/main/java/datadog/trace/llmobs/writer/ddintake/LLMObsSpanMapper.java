package datadog.trace.llmobs.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;
import static datadog.json.JsonMapper.toJson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.Writable;
import datadog.trace.api.DDTags;
import datadog.trace.api.intake.TrackType;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import okhttp3.RequestBody;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.jackson.dataformat.MessagePackFactory;
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
  private static final byte[] ML_APP = "ml_app".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METRICS = "metrics".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TAGS = "tags".getBytes(StandardCharsets.UTF_8);

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

    writable.writeUTF8("spans".getBytes());
    writable.startArray(llmobsSpans.size());
    for (CoreSpan<?> span : llmobsSpans) {
      LOGGER.debug("MAPPING SPAN {}", span);
      writable.startMap(11);
      // 1
      writable.writeUTF8(SPAN_ID);
      writable.writeString(String.valueOf(span.getSpanId()), null);

      // 2
      writable.writeUTF8(TRACE_ID);
      writable.writeString(span.getTraceId().toHexString(), null);

      // 3
      writable.writeUTF8(PARENT_ID);
      // TODO fix after parent ID tracking is in place
      writable.writeString("undefined", null);

      // 4
      writable.writeUTF8(NAME);
      writable.writeString(span.getOperationName(), null);

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

  private static boolean isLLMObsSpan(CoreSpan<?> span) {
    CharSequence type = span.getType();
    return type != null && type.toString().contentEquals(InternalSpanTypes.LLMOBS);
  }

  @Override
  public Payload newPayload() {
    return new PayloadV2();
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
          if (TAGS_FOR_REMAPPING.contains(key.replaceFirst(LLMOBS_TAG_PREFIX, ""))) {
            tagsToRemapToMeta.put(key, tag.getValue());
          } else {
            ++tagsSize;
          }
        }
      }

      if (!spanKind.equals("unknown")) {
        metadata.getTags().remove(SPAN_KIND_TAG_KEY);
      }

      LOGGER.warn("TAGS TO REMAP {} TAGS {} METRICS {}", tagsToRemapToMeta, tagsSize, metricsSize);

      // write metrics (9)
      writable.writeUTF8(METRICS);
      writable.startMap(metricsSize);
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        if (tag.getKey().startsWith(LLMOBS_METRIC_PREFIX) && tag.getValue() instanceof Number) {
          writable.writeString(tag.getKey().replaceFirst(LLMOBS_METRIC_PREFIX, ""), null);
          writable.writeDouble((double) tag.getValue());
        }
      }

      // write tags (10)
      writable.writeUTF8(TAGS);
      writable.startArray(tagsSize + 1);
      writable.writeString("language:java", null);
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        LOGGER.warn("ON TAG {}", tag.getKey());

        Object value = tag.getValue();
        if (!tagsToRemapToMeta.containsKey(tag.getKey())
            && tag.getKey().startsWith(LLMOBS_TAG_PREFIX)) {
          String key = tag.getKey().replaceFirst(LLMOBS_TAG_PREFIX, "");
          String toWrite = key + ":" + value;
          writable.writeObject(toWrite, null);
          LOGGER.warn("WROTE TAG {}", toWrite);
        }

        LOGGER.warn("SKIPPED {}", tag.getKey());
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
        String key = tag.getKey().replaceFirst(LLMOBS_TAG_PREFIX, "");
        Object val = tag.getValue();
        if (key.equals(INPUT) || key.equals(OUTPUT)) {
          if (val instanceof String) {
            key += ".value";
          } else {
            key += ".messages";
            LOGGER.warn("INOBJ {}", val);
            //            val = convertToJson(val);
            LOGGER.warn("OUTOBJ {}", val);
          }
        } else if (key.equals(LLMObsTags.METADATA) && val instanceof Map) {

          Map<String, Object> metadataMap = (Map) val;
          LOGGER.warn("METADATA {}", metadataMap);
          writable.writeUTF8(METADATA);
          writable.startMap(metadataMap.size());
          for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
            writable.writeString(entry.getKey(), null);
            writable.writeObject(entry.getValue(), null);
          }
          continue;
        }
        writable.writeString(key, null);
        writable.writeObject(val, null);
      }
    }

    private static boolean JSONSerializable(Object o) {
      return o instanceof String
          || o instanceof String[]
          || o instanceof Iterable
          || o instanceof Map
          || o instanceof Number
          || o instanceof Boolean;
    }

    private static String convertToJson(Object o) {
      if (o instanceof String) {
        return toJson((String) o);
      }
      if (o instanceof String[]) {
        return toJson((String[]) o);
      }
      if (o instanceof Map) {
        return toJson((Map) o);
      }
      if (o instanceof Collection) {
        return toJson((Collection) o);
      }
      if (o instanceof Number || o instanceof Boolean) {
        return o.toString();
      }
      return o.toString();
    }
  }

  private static class PayloadV2 extends Payload {
    private static int size = 0;

    private PayloadV2() {}

    @Override
    public int sizeInBytes() {
      if (traceCount() == 0) {
        return msgpackMapHeaderSize(0);
      }

      int size = body.array().length;
      return size;
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

      // working json
      //      String jsonStr = "{\"_dd.stage\": \"raw\", \"_dd.tracer_version\": \"java\",
      // \"event_type\": \"span\", \"spans\": [{\"trace_id\": \"deadbeef\", \"span_id\":
      // \"202502061557\", \"parent_id\": \"undefined\", \"name\": \"agent_root\", \"start_ns\":
      // 1738867324412779000, \"duration\": 225467000, \"status\": \"ok\", \"meta\": {\"span.kind\":
      // \"agent\", \"input\": {\"value\": \"agent root input0.8541088675419499\"}, \"output\":
      // {\"value\": \"output\"}, \"metadata\": {}}, \"metrics\": {}, \"tags\": [\"version:\",
      // \"env:staging\", \"service:test-gary-java\", \"source:integration\",
      // \"ml_app:test-gary-java\", \"language:java\", \"error:0\"]}]}";
      //      RequestBody reqBody = jsonRequestBodyOf(jsonStr.getBytes());

      List<ByteBuffer> buffers;
      if (traceCount() == 0) {
        buffers = Collections.singletonList(msgpackMapHeader(0));
      } else {
        buffers = Collections.singletonList(body);
      }

      RequestBody requestBody = gzippedMsgpackRequestBodyOf(buffers);

      Map<String, Object> testunpack = parse(body.array());

      LOGGER.warn("LLMOBS TOREQUEST UNPACKED {}", testunpack);

      return requestBody;
    }
  }

  private static Map<String, Object> parse(byte[] value) {
    MessagePackFactory messagePackFactory = new MessagePackFactory();
    ObjectMapper mapper = new ObjectMapper(messagePackFactory);

    try (JsonParser parser = messagePackFactory.createParser(value)) {
      parser.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
      parser.enable(
          JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER
              .mappedFeature()); // allow escaped single quotes \'
      Map<String, Object> map = (Map<String, Object>) mapper.readValue(parser, Object.class);
      if (map == null) {
        return null;
      }
      return map;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String readMsgPackToStr(ByteBuffer body) {
    MessageUnpacker unpacker = null;
    String responseJson = "";
    try {
      unpacker =
          new MessagePack.UnpackerConfig()
              .withStringDecoderBufferSize(
                  16 * 1024) // If your data contains many large strings (the default is 8k)
              .newUnpacker(body);

      ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());

      try {
        JsonNode jsonNode = mapper.readTree(body.array());
        responseJson = jsonNode.toString();
        LOGGER.warn("Response Json {}", responseJson);
      } catch (IOException e) {
        LOGGER.error("Exception occurred", e);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return responseJson;
  }
}

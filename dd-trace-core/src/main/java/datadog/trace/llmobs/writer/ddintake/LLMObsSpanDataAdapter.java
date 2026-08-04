package datadog.trace.llmobs.writer.ddintake;

import static java.util.Objects.requireNonNull;

import datadog.trace.api.DDTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpanData;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapts the internal tag representation of an LLM Observability span to the public API. */
final class LLMObsSpanDataAdapter implements LLMObsSpanData {
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  private static final String INPUT_TAG = LLMOBS_TAG_PREFIX + "input";
  private static final String OUTPUT_TAG = LLMOBS_TAG_PREFIX + "output";
  private static final String SPAN_KIND_TAG = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;

  private enum IOType {
    NONE,
    MESSAGES,
    DOCUMENTS,
    VALUE
  }

  private final CoreSpan<?> span;
  private final String kind;
  private final Object originalInput;
  private final Object originalOutput;
  private final IOType inputType;
  private final IOType outputType;
  private List<LLMObs.LLMMessage> input;
  private List<LLMObs.LLMMessage> output;

  LLMObsSpanDataAdapter(CoreSpan<?> span) {
    this.span = span;
    Object rawKind = span.getTag(SPAN_KIND_TAG);
    kind = rawKind == null ? "unknown" : String.valueOf(rawKind);
    originalInput = span.getTag(INPUT_TAG);
    originalOutput = span.getTag(OUTPUT_TAG);
    inputType = ioType(kind, originalInput, true);
    outputType = ioType(kind, originalOutput, false);
    input = asMessages(originalInput, inputType);
    output = asMessages(originalOutput, outputType);
  }

  @Override
  public String getKind() {
    return kind;
  }

  @Override
  public List<LLMObs.LLMMessage> getInput() {
    return input;
  }

  @Override
  public void setInput(List<LLMObs.LLMMessage> input) {
    this.input = new ArrayList<>(requireNonNull(input, "input"));
  }

  @Override
  public List<LLMObs.LLMMessage> getOutput() {
    return output;
  }

  @Override
  public void setOutput(List<LLMObs.LLMMessage> output) {
    this.output = new ArrayList<>(requireNonNull(output, "output"));
  }

  @Override
  public String getTag(String key) {
    Object value = span.getTag(LLMOBS_TAG_PREFIX + key);
    if (value == null && "error".equals(key)) {
      return String.valueOf(span.getError());
    }
    if (value == null && "error_type".equals(key)) {
      value = span.getTag(DDTags.ERROR_TYPE);
    }
    return value == null ? null : String.valueOf(value);
  }

  void apply(LLMObsSpanData processedSpan) {
    applyIO(
        span,
        INPUT_TAG,
        originalInput,
        inputType,
        new ArrayList<>(requireNonNull(processedSpan.getInput(), "processed input")));
    applyIO(
        span,
        OUTPUT_TAG,
        originalOutput,
        outputType,
        new ArrayList<>(requireNonNull(processedSpan.getOutput(), "processed output")));
  }

  private static IOType ioType(String kind, Object value, boolean input) {
    if (value == null) {
      if (Tags.LLMOBS_LLM_SPAN_KIND.equals(kind)) {
        return IOType.MESSAGES;
      }
      if ((input && Tags.LLMOBS_EMBEDDING_SPAN_KIND.equals(kind))
          || (!input && Tags.LLMOBS_RETRIEVAL_SPAN_KIND.equals(kind))) {
        return IOType.DOCUMENTS;
      }
      return IOType.VALUE;
    }
    Object unwrapped = unwrapMessages(value);
    if (Tags.LLMOBS_LLM_SPAN_KIND.equals(kind)) {
      return unwrapped instanceof List && allMessages((List<?>) unwrapped)
          ? IOType.MESSAGES
          : IOType.NONE;
    }
    if (((input && Tags.LLMOBS_EMBEDDING_SPAN_KIND.equals(kind))
            || (!input && Tags.LLMOBS_RETRIEVAL_SPAN_KIND.equals(kind)))
        && value instanceof List
        && allDocuments((List<?>) value)) {
      return IOType.DOCUMENTS;
    }
    return IOType.VALUE;
  }

  @SuppressWarnings("unchecked")
  private static List<LLMObs.LLMMessage> asMessages(Object value, IOType type) {
    if (value == null || type == IOType.NONE) {
      return new ArrayList<>();
    }
    if (type == IOType.MESSAGES) {
      return new ArrayList<>((List<LLMObs.LLMMessage>) unwrapMessages(value));
    }
    if (type == IOType.DOCUMENTS) {
      List<LLMObs.LLMMessage> messages = new ArrayList<>(((List<?>) value).size());
      for (Object valueElement : (List<?>) value) {
        LLMObs.Document document = (LLMObs.Document) valueElement;
        messages.add(LLMObs.LLMMessage.from("", document.getText()));
      }
      return messages;
    }
    List<LLMObs.LLMMessage> messages = new ArrayList<>(1);
    messages.add(LLMObs.LLMMessage.from("", String.valueOf(value)));
    return messages;
  }

  private static Object unwrapMessages(Object value) {
    if (value instanceof Map) {
      return ((Map<?, ?>) value).get("messages");
    }
    return value;
  }

  private static boolean allMessages(List<?> values) {
    for (Object value : values) {
      if (!(value instanceof LLMObs.LLMMessage)) {
        return false;
      }
    }
    return true;
  }

  private static boolean allDocuments(List<?> values) {
    for (Object value : values) {
      if (!(value instanceof LLMObs.Document)) {
        return false;
      }
    }
    return true;
  }

  private static void applyIO(
      CoreSpan<?> span,
      String tag,
      Object originalValue,
      IOType type,
      List<LLMObs.LLMMessage> messages) {
    if (type == IOType.NONE) {
      return;
    }
    if (messages.isEmpty()) {
      if (originalValue == null) {
        return;
      }
      if (type == IOType.MESSAGES && originalValue instanceof Map) {
        Map<Object, Object> updatedValue = new HashMap<>((Map<?, ?>) originalValue);
        updatedValue.remove("messages");
        if (updatedValue.isEmpty()) {
          span.removeTag(tag);
        } else {
          span.setTag(tag, updatedValue);
        }
      } else {
        span.removeTag(tag);
      }
      return;
    }
    if (type == IOType.MESSAGES) {
      if (originalValue instanceof Map) {
        Map<Object, Object> updatedValue = new HashMap<>((Map<?, ?>) originalValue);
        updatedValue.put("messages", messages);
        span.setTag(tag, updatedValue);
      } else {
        span.setTag(tag, messages);
      }
    } else if (type == IOType.DOCUMENTS) {
      List<LLMObs.Document> documents = new ArrayList<>(messages.size());
      for (LLMObs.LLMMessage message : messages) {
        documents.add(LLMObs.Document.from(message.getContent()));
      }
      span.setTag(tag, documents);
    } else {
      span.setTag(tag, messages.get(0).getContent());
    }
  }
}

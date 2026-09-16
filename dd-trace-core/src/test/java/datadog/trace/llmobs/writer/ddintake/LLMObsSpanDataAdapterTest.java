package datadog.trace.llmobs.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LLMObsSpanDataAdapterTest {
  private static final String INPUT_TAG = "_ml_obs_tag.input";
  private static final String OUTPUT_TAG = "_ml_obs_tag.output";
  private static final String SPAN_KIND_TAG = "_ml_obs_tag.span.kind";

  @Test
  void convertsAndAppliesEmbeddingDocumentsAndValueOutput() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_EMBEDDING_SPAN_KIND);
    when(span.getTag(INPUT_TAG))
        .thenReturn(
            Collections.singletonList(
                LLMObs.Document.from("original document", "source.txt", "doc-123", 0.75)));
    when(span.getTag(OUTPUT_TAG)).thenReturn("original output");

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);

    assertEquals(Tags.LLMOBS_EMBEDDING_SPAN_KIND, adapter.getKind());
    assertEquals("original document", adapter.getInput().get(0).getContent());
    assertEquals("original output", adapter.getOutput().get(0).getContent());

    adapter.setInput(Collections.singletonList(LLMObs.LLMMessage.from("", "processed document")));
    adapter.setOutput(Collections.singletonList(LLMObs.LLMMessage.from("", "processed output")));
    adapter.apply(adapter);

    ArgumentCaptor<Object> inputCaptor = ArgumentCaptor.forClass(Object.class);
    verify(span).setTag(eq(INPUT_TAG), inputCaptor.capture());
    List<?> documents = (List<?>) inputCaptor.getValue();
    LLMObs.Document document = (LLMObs.Document) documents.get(0);
    assertEquals("processed document", document.getText());
    assertEquals("source.txt", document.getName());
    assertEquals("doc-123", document.getId());
    assertEquals(Double.valueOf(0.75), document.getScore());
    verify(span).setTag(OUTPUT_TAG, "processed output");
  }

  @Test
  void convertsAndAppliesRetrievalOutputDocuments() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_RETRIEVAL_SPAN_KIND);
    when(span.getTag(OUTPUT_TAG))
        .thenReturn(
            Collections.singletonList(
                LLMObs.Document.from("original document", "result.txt", "doc-456", 0.9)));

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);

    assertEquals("original document", adapter.getOutput().get(0).getContent());

    adapter.setOutput(Collections.singletonList(LLMObs.LLMMessage.from("", "processed document")));
    adapter.apply(adapter);

    ArgumentCaptor<Object> outputCaptor = ArgumentCaptor.forClass(Object.class);
    verify(span).setTag(eq(OUTPUT_TAG), outputCaptor.capture());
    List<?> documents = (List<?>) outputCaptor.getValue();
    LLMObs.Document document = (LLMObs.Document) documents.get(0);
    assertEquals("processed document", document.getText());
    assertEquals("result.txt", document.getName());
    assertEquals("doc-456", document.getId());
    assertEquals(Double.valueOf(0.9), document.getScore());
  }

  @Test
  void appliesInPlaceClearToMessageInputAndOutput() {
    CoreSpan<?> span = mock(CoreSpan.class);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("messages", Collections.singletonList(LLMObs.LLMMessage.from("user", "input")));
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_LLM_SPAN_KIND);
    when(span.getTag(INPUT_TAG)).thenReturn(input);
    when(span.getTag(OUTPUT_TAG))
        .thenReturn(Collections.singletonList(LLMObs.LLMMessage.from("assistant", "output")));

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
    adapter.getInput().clear();
    adapter.getOutput().clear();
    adapter.apply(adapter);

    verify(span).removeTag(INPUT_TAG);
    verify(span).removeTag(OUTPUT_TAG);
  }

  @Test
  void ignoresMalformedLlmIo() {
    CoreSpan<?> span = mock(CoreSpan.class);
    List<String> invalidMessages = Collections.singletonList("not a message");
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_LLM_SPAN_KIND);
    when(span.getTag(INPUT_TAG)).thenReturn(invalidMessages);
    when(span.getTag(OUTPUT_TAG)).thenReturn(Collections.singletonMap("messages", invalidMessages));

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);

    assertEquals(Collections.emptyList(), adapter.getInput());
    assertEquals(Collections.emptyList(), adapter.getOutput());
    adapter.apply(adapter);
  }

  @Test
  void addsMissingInputAndOutput() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_LLM_SPAN_KIND);

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
    List<LLMObs.LLMMessage> input =
        Collections.singletonList(LLMObs.LLMMessage.from("user", "input"));
    List<LLMObs.LLMMessage> output =
        Collections.singletonList(LLMObs.LLMMessage.from("assistant", "output"));
    adapter.setInput(input);
    adapter.setOutput(output);
    adapter.apply(adapter);

    verify(span).setTag(INPUT_TAG, input);
    verify(span).setTag(OUTPUT_TAG, output);
  }

  @Test
  void addsMessagesToPromptOnlyInput() {
    CoreSpan<?> span = mock(CoreSpan.class);
    Map<String, Object> prompt = Collections.singletonMap("id", "prompt-id");
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_LLM_SPAN_KIND);
    when(span.getTag(INPUT_TAG)).thenReturn(Collections.singletonMap("prompt", prompt));

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
    adapter.setInput(Collections.singletonList(LLMObs.LLMMessage.from("user", "input")));
    adapter.apply(adapter);

    ArgumentCaptor<Object> inputCaptor = ArgumentCaptor.forClass(Object.class);
    verify(span).setTag(eq(INPUT_TAG), inputCaptor.capture());
    Map<?, ?> input = (Map<?, ?>) inputCaptor.getValue();
    assertEquals(prompt, input.get("prompt"));
    assertEquals(adapter.getInput(), input.get("messages"));
  }

  @Test
  void preservesIoWhenProcessorDoesNotModifyIt() {
    CoreSpan<?> span = mock(CoreSpan.class);
    Map<String, Object> input = Collections.singletonMap("key", "input");
    List<String> output = Collections.singletonList("output");
    when(span.getTag(INPUT_TAG)).thenReturn(input);
    when(span.getTag(OUTPUT_TAG)).thenReturn(output);

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
    clearInvocations(span);
    adapter.apply(adapter);

    verifyNoInteractions(span);
  }

  @Test
  void readsPublicAndErrorTags() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.getTag("_ml_obs_tag.custom")).thenReturn(123);
    when(span.getError()).thenReturn(1);
    when(span.getTag(DDTags.ERROR_TYPE)).thenReturn("java.lang.IllegalStateException");

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);

    assertEquals("unknown", adapter.getKind());
    assertEquals("123", adapter.getTag("custom"));
    assertEquals("1", adapter.getTag("error"));
    assertEquals("java.lang.IllegalStateException", adapter.getTag("error_type"));
    assertNull(adapter.getTag("missing"));
  }

  @Test
  void returnsNullForAbsentErrorTag() {
    CoreSpan<?> span = mock(CoreSpan.class);

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);

    assertNull(adapter.getTag("error"));
  }
}

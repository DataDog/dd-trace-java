package datadog.trace.llmobs.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        .thenReturn(Collections.singletonList(LLMObs.Document.from("original document")));
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
    assertEquals("processed document", ((LLMObs.Document) documents.get(0)).getText());
    verify(span).setTag(OUTPUT_TAG, "processed output");
  }

  @Test
  void removesEmptyMessageInputAndOutput() {
    CoreSpan<?> span = mock(CoreSpan.class);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("messages", Collections.singletonList(LLMObs.LLMMessage.from("user", "input")));
    when(span.getTag(SPAN_KIND_TAG)).thenReturn(Tags.LLMOBS_LLM_SPAN_KIND);
    when(span.getTag(INPUT_TAG)).thenReturn(input);
    when(span.getTag(OUTPUT_TAG))
        .thenReturn(Collections.singletonList(LLMObs.LLMMessage.from("assistant", "output")));

    LLMObsSpanDataAdapter adapter = new LLMObsSpanDataAdapter(span);
    adapter.setInput(Collections.emptyList());
    adapter.setOutput(Collections.emptyList());
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
}

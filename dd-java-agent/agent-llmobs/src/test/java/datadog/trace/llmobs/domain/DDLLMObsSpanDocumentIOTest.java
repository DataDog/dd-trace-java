package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DDLLMObsSpanDocumentIOTest {
  private static final String INPUT_TAG = "_ml_obs_tag.input";
  private static final String OUTPUT_TAG = "_ml_obs_tag.output";
  private static final Field SPAN_FIELD;

  private static CoreTracer tracer;

  static {
    try {
      SPAN_FIELD = DDLLMObsSpan.class.getDeclaredField("span");
      SPAN_FIELD.setAccessible(true);
    } catch (ReflectiveOperationException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
  }

  @AfterAll
  static void closeTracer() {
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
  }

  @Test
  void formatsEmbeddingStringInputAsDocument() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(Tags.LLMOBS_EMBEDDING_SPAN_KIND);
    try {
      llmObsSpan.annotateIO("embedding input", "embedding output");

      AgentSpan span = (AgentSpan) SPAN_FIELD.get(llmObsSpan);
      assertDocument(span.getTag(INPUT_TAG), "embedding input");
      assertEquals("embedding output", span.getTag(OUTPUT_TAG));
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void formatsRetrievalStringOutputAsDocument() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(Tags.LLMOBS_RETRIEVAL_SPAN_KIND);
    try {
      llmObsSpan.annotateIO("retrieval input", "retrieval output");

      AgentSpan span = (AgentSpan) SPAN_FIELD.get(llmObsSpan);
      assertEquals("retrieval input", span.getTag(INPUT_TAG));
      assertDocument(span.getTag(OUTPUT_TAG), "retrieval output");
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void acceptsEmbeddingDocumentInputs() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(Tags.LLMOBS_EMBEDDING_SPAN_KIND);
    List<LLMObs.Document> documents =
        Arrays.asList(
            LLMObs.Document.from("first input", "first.txt", "input-1", 0.5),
            LLMObs.Document.from("second input"));
    try {
      llmObsSpan.annotateIO(documents, "embedding output");

      AgentSpan span = (AgentSpan) SPAN_FIELD.get(llmObsSpan);
      assertEquals(documents, span.getTag(INPUT_TAG));
      assertDocument(documents.get(0), "first input", "first.txt", "input-1", 0.5);
      assertEquals("embedding output", span.getTag(OUTPUT_TAG));
    } finally {
      llmObsSpan.finish();
    }
  }

  @Test
  void acceptsRetrievalDocumentOutputs() throws IllegalAccessException {
    DDLLMObsSpan llmObsSpan = newSpan(Tags.LLMOBS_RETRIEVAL_SPAN_KIND);
    List<LLMObs.Document> documents =
        Arrays.asList(
            LLMObs.Document.from("first output", "result.txt", "output-1", 0.95),
            LLMObs.Document.from("second output"));
    try {
      llmObsSpan.annotateIO("retrieval input", documents);

      AgentSpan span = (AgentSpan) SPAN_FIELD.get(llmObsSpan);
      assertEquals("retrieval input", span.getTag(INPUT_TAG));
      assertEquals(documents, span.getTag(OUTPUT_TAG));
      assertDocument(documents.get(0), "first output", "result.txt", "output-1", 0.95);
    } finally {
      llmObsSpan.finish();
    }
  }

  private static DDLLMObsSpan newSpan(String kind) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, "span", "ml-app", null, "service", tags);
  }

  private static void assertDocument(Object value, String expectedText) {
    List<?> documents = assertInstanceOf(List.class, value);
    assertEquals(1, documents.size());
    LLMObs.Document document = assertInstanceOf(LLMObs.Document.class, documents.get(0));
    assertEquals(expectedText, document.getText());
    assertNull(document.getName());
    assertNull(document.getId());
    assertNull(document.getScore());
  }

  private static void assertDocument(
      LLMObs.Document document,
      String expectedText,
      String expectedName,
      String expectedId,
      double expectedScore) {
    assertEquals(expectedText, document.getText());
    assertEquals(expectedName, document.getName());
    assertEquals(expectedId, document.getId());
    assertEquals(expectedScore, document.getScore());
  }
}

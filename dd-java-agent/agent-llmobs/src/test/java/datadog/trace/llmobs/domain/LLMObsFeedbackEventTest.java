package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.DDTraceApiInfo;
import datadog.trace.api.llmobs.LLMObs;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LLMObsFeedbackEventTest {

  private static final JsonAdapter<Map<String, Object>> JSON_READER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private static Map<String, Object> serializeOne(LLMObs.Feedback feedback, String mlApp)
      throws IOException {
    String body =
        LLMObsFeedbackEvent.batchSerializer()
            .toJson(Collections.singletonList(new LLMObsFeedbackEvent(feedback, mlApp)));

    Map<String, Object> parsed = JSON_READER.fromJson(body);
    Map<String, Object> data = asMap(parsed.get("data"));
    assertEquals("evaluation_metric", data.get("type"));

    List<?> metrics = (List<?>) asMap(data.get("attributes")).get("metrics");
    assertEquals(1, metrics.size());
    return asMap(metrics.get(0));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @Test
  void testFeedbackIsToldApartFromEvaluationsByEventKind() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", "end_user")
                .timestampMs(1700000000000L)
                .build(),
            "my-app");

    assertEquals("feedback", event.get("event_kind"));
    assertEquals("thumbs", event.get("label"));
    assertEquals("boolean", event.get("metric_type"));
    assertEquals(Boolean.TRUE, event.get("boolean_value"));
    assertEquals("my-app", event.get("ml_app"));
    assertEquals(1.7e12, event.get("timestamp_ms"));
    assertEquals("123", event.get("span_id"));
  }

  @Test
  void testExactlyOneTargetIsEmitted() throws IOException {
    Map<String, Object> bySessionId =
        serializeOne(
            LLMObs.Feedback.builder()
                .sessionId("session-2")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .build(),
            "my-app");

    assertEquals("session-2", bySessionId.get("session_id"));
    assertFalse(bySessionId.containsKey("span_id"), bySessionId.toString());
    assertFalse(bySessionId.containsKey("trace_id"), bySessionId.toString());
    assertFalse(bySessionId.containsKey("feedback_join_key"), bySessionId.toString());
  }

  @Test
  void testJoinKeyTargetCarriesNoSpanNorTraceIdentifier() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .feedbackJoinKey("incident-123")
                .label("user_comment")
                .textValue("The investigation missed the customer impact.")
                .submitter("user-123", "end_user")
                .assessment(LLMObs.Feedback.Assessment.FAIL)
                .build(),
            "incident-agent");

    assertEquals("incident-123", event.get("feedback_join_key"));
    assertEquals("text", event.get("metric_type"));
    assertEquals("The investigation missed the customer impact.", event.get("text_value"));
    assertEquals("fail", event.get("assessment"));
    assertFalse(event.containsKey("span_id"), event.toString());
    assertFalse(event.containsKey("trace_id"), event.toString());
  }

  @Test
  void testExactlyOneValueKeyIsEmittedPerMetricType() throws IOException {
    Map<String, Object> categorical =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("satisfaction")
                .categoricalValue("satisfied")
                .submitter("user-123", null)
                .build(),
            "my-app");
    assertEquals("satisfied", categorical.get("categorical_value"));
    assertFalse(categorical.containsKey("boolean_value"), categorical.toString());
    assertFalse(categorical.containsKey("text_value"), categorical.toString());

    Map<String, Object> score =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("rating")
                .scoreValue(0.75)
                .submitter("user-123", null)
                .build(),
            "my-app");
    assertEquals(0.75, score.get("score_value"));

    Map<String, Object> json =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("details")
                .jsonValue(Collections.singletonMap("missing", "customer impact"))
                .submitter("user-123", null)
                .build(),
            "my-app");
    assertEquals(Collections.singletonMap("missing", "customer impact"), json.get("json_value"));
  }

  @Test
  void testSubmitterTypeIsOmittedWhenAbsent() throws IOException {
    Map<String, Object> withType =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", "end_user")
                .build(),
            "my-app");
    assertEquals("user-123", asMap(withType.get("submitter")).get("id"));
    assertEquals("end_user", asMap(withType.get("submitter")).get("type"));

    Map<String, Object> withoutType =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .build(),
            "my-app");
    assertEquals("user-123", asMap(withoutType.get("submitter")).get("id"));
    assertFalse(asMap(withoutType.get("submitter")).containsKey("type"), withoutType.toString());
  }

  @Test
  void testAssessmentAndReasoningAreOmittedWhenAbsent() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .build(),
            "my-app");

    assertFalse(event.containsKey("assessment"), event.toString());
    assertFalse(event.containsKey("reasoning"), event.toString());
  }

  @Test
  void testReasoningIsEmittedWhenPresent() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(false)
                .submitter("user-123", null)
                .reasoning("did not answer the question")
                .build(),
            "my-app");

    assertEquals("did not answer the question", event.get("reasoning"));
  }

  @Test
  void testTagsCarryTracerVersionAndMlAppAlongsideUserTags() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .tag("source", "web-ui")
                .build(),
            "my-app");

    List<?> tags = (List<?>) event.get("tags");
    assertTrue(tags.contains("ddtrace.version:" + DDTraceApiInfo.VERSION), tags.toString());
    assertTrue(tags.contains("ml_app:my-app"), tags.toString());
    assertTrue(tags.contains("source:web-ui"), tags.toString());
  }

  @Test
  void testTagsAreEmittedEvenWithoutUserTags() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .build(),
            "my-app");

    assertEquals(
        Arrays.asList("ddtrace.version:" + DDTraceApiInfo.VERSION, "ml_app:my-app"),
        event.get("tags"));
  }

  @Test
  void testABatchIsSerializedAsSeveralMetricsInOneEnvelope() throws IOException {
    LLMObs.Feedback thumbs =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("thumbs")
            .booleanValue(true)
            .submitter("user-123", null)
            .build();
    LLMObs.Feedback comment =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("comment")
            .textValue("helpful")
            .submitter("user-123", null)
            .build();

    String body =
        LLMObsFeedbackEvent.batchSerializer()
            .toJson(
                Arrays.asList(
                    new LLMObsFeedbackEvent(thumbs, "my-app"),
                    new LLMObsFeedbackEvent(comment, "my-app")));

    Map<String, Object> data = asMap(JSON_READER.fromJson(body).get("data"));
    List<?> metrics = (List<?>) asMap(data.get("attributes")).get("metrics");

    assertEquals(2, metrics.size());
    assertEquals("thumbs", asMap(metrics.get(0)).get("label"));
    assertEquals("comment", asMap(metrics.get(1)).get("label"));
  }

  @Test
  void testMlAppFallsBackToTheProvidedDefault() throws IOException {
    Map<String, Object> event =
        serializeOne(
            LLMObs.Feedback.builder()
                .spanId("123")
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null)
                .build(),
            "fallback-app");

    assertEquals("fallback-app", event.get("ml_app"));
    List<?> tags = (List<?>) event.get("tags");
    assertTrue(tags.contains("ml_app:fallback-app"), tags.toString());
  }
}

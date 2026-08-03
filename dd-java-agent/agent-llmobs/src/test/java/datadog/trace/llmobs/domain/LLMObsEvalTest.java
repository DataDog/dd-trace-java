package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the v1 eval metric payload.
 *
 * <p>Evaluations and feedback now share {@link datadog.trace.llmobs.LLMObsIntakeWorker}, so the
 * eval payload reaches the intake through {@link LLMObsEval#batchSerializer()} rather than through
 * an adapter held by the worker. These tests exist to prove that indirection changed nothing on the
 * wire: no {@code event_kind}, no {@code submitter}, and the same key set as before.
 */
class LLMObsEvalTest {

  private static final JsonAdapter<Map<String, Object>> JSON_READER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private static List<?> serialize(LLMObsEval... evals) throws IOException {
    String body = LLMObsEval.batchSerializer().toJson(Arrays.asList(evals));

    Map<String, Object> data = asMap(JSON_READER.fromJson(body).get("data"));
    assertEquals("evaluation_metric", data.get("type"));
    return (List<?>) asMap(data.get("attributes")).get("metrics");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @Test
  void testScoreEvalCarriesTheV1KeySetAndNothingElse() throws IOException {
    List<?> metrics =
        serialize(
            new LLMObsEval.Score(
                "abc123",
                42L,
                1700000000000L,
                "my-app",
                "sentiment",
                Collections.singletonMap("source", "web-ui"),
                0.75));

    assertEquals(1, metrics.size());
    Map<String, Object> metric = asMap(metrics.get(0));

    assertEquals("abc123", metric.get("trace_id"));
    assertEquals("42", metric.get("span_id"));
    assertEquals(1.7e12, metric.get("timestamp_ms"));
    assertEquals("my-app", metric.get("ml_app"));
    assertEquals("score", metric.get("metric_type"));
    assertEquals("sentiment", metric.get("label"));
    assertEquals(0.75, metric.get("score_value"));
    assertEquals(Collections.singletonList("source:web-ui"), metric.get("tags"));

    // Feedback-only keys must never leak into the v1 payload.
    assertFalse(metric.containsKey("event_kind"), metric.toString());
    assertFalse(metric.containsKey("submitter"), metric.toString());
    assertFalse(metric.containsKey("session_id"), metric.toString());
    assertFalse(metric.containsKey("feedback_join_key"), metric.toString());
    assertFalse(metric.containsKey("assessment"), metric.toString());
    assertFalse(metric.containsKey("reasoning"), metric.toString());
  }

  @Test
  void testCategoricalEvalCarriesTheV1KeySet() throws IOException {
    List<?> metrics =
        serialize(
            new LLMObsEval.Categorical(
                "abc123", 42L, 1700000000000L, "my-app", "tone", null, "positive"));

    Map<String, Object> metric = asMap(metrics.get(0));

    assertEquals("categorical", metric.get("metric_type"));
    assertEquals("positive", metric.get("categorical_value"));
    assertFalse(metric.containsKey("score_value"), metric.toString());
    // A null tag map is omitted rather than serialized as an empty list.
    assertFalse(metric.containsKey("tags"), metric.toString());
    assertFalse(metric.containsKey("event_kind"), metric.toString());
  }

  @Test
  void testABatchMixesScoreAndCategoricalInOneEnvelope() throws IOException {
    List<?> metrics =
        serialize(
            new LLMObsEval.Score("abc123", 42L, 1700000000000L, "my-app", "sentiment", null, 0.75),
            new LLMObsEval.Categorical(
                "abc123", 42L, 1700000000000L, "my-app", "tone", null, "positive"));

    assertEquals(2, metrics.size());
    assertEquals(0.75, asMap(metrics.get(0)).get("score_value"));
    assertEquals("positive", asMap(metrics.get(1)).get("categorical_value"));
  }
}

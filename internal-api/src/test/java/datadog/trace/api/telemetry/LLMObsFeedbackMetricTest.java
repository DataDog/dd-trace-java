package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code feedback_submitted} telemetry metric, the Java counterpart of {@code
 * record_llmobs_submit_feedback} in dd-trace-py.
 */
class LLMObsFeedbackMetricTest {

  private LLMObsMetricCollector collector;

  @BeforeEach
  void setup() {
    collector = LLMObsMetricCollector.get();
    // The collector is a singleton shared with the rest of the suite.
    collector.drain();
  }

  private LLMObsMetricCollector.LLMObsMetric drainOne() {
    Collection<LLMObsMetricCollector.LLMObsMetric> drained = collector.drain();
    assertEquals(1, drained.size(), drained.toString());
    return drained.iterator().next();
  }

  @Test
  void testAnAcceptedSubmissionIsCountedWithoutError() {
    collector.recordFeedbackSubmitted("boolean", "span_id", null);

    LLMObsMetricCollector.LLMObsMetric metric = drainOne();
    assertEquals("mlobs", metric.namespace);
    assertEquals("feedback_submitted", metric.metricName);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value.longValue());

    List<String> tags = metric.tags;
    assertTrue(tags.contains("error:0"), tags.toString());
    assertTrue(tags.contains("metric_type:boolean"), tags.toString());
    assertTrue(tags.contains("target_type:span_id"), tags.toString());
    assertFalse(tags.toString().contains("error_type"), tags.toString());
  }

  @Test
  void testARejectedSubmissionCarriesItsErrorType() {
    collector.recordFeedbackSubmitted("text", "feedback_join_key", "invalid_submitter");

    List<String> tags = drainOne().tags;
    assertTrue(tags.contains("error:1"), tags.toString());
    assertTrue(tags.contains("error_type:invalid_submitter"), tags.toString());
    assertTrue(tags.contains("metric_type:text"), tags.toString());
    assertTrue(tags.contains("target_type:feedback_join_key"), tags.toString());
  }

  @Test
  void testUndeterminedMetricAndTargetTypesFallBackToOther() {
    // A feedback rejected before its target or value was set still gets counted.
    collector.recordFeedbackSubmitted(null, null, "invalid_target_count");

    List<String> tags = drainOne().tags;
    assertTrue(tags.contains("error:1"), tags.toString());
    assertTrue(tags.contains("metric_type:other"), tags.toString());
    assertTrue(tags.contains("target_type:other"), tags.toString());
  }
}

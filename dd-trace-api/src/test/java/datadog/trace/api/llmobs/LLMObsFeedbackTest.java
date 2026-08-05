package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.llmobs.noop.NoOpLLMObsFeedbackProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LLMObsFeedbackTest {

  private static Object originalFeedbackProcessor;

  @BeforeAll
  static void setupSpec() throws Exception {
    originalFeedbackProcessor = getStaticField("FEEDBACK_PROCESSOR");
  }

  @AfterAll
  static void cleanupSpec() throws Exception {
    setStaticField("FEEDBACK_PROCESSOR", originalFeedbackProcessor);
  }

  @AfterEach
  void cleanup() throws Exception {
    setStaticField("FEEDBACK_PROCESSOR", NoOpLLMObsFeedbackProcessor.INSTANCE);
  }

  private static LLMObs.Feedback.Builder validBuilder() {
    return LLMObs.Feedback.builder()
        .spanId("123")
        .label("thumbs")
        .booleanValue(true)
        .submitter("user-123", "end_user");
  }

  /**
   * Asserts that a builder produces an invalid feedback, without ever throwing. Validation is
   * deferred to {@link LLMObs#submitFeedback} so that instrumented code stays safe when LLM
   * Observability is disabled or the agent is not attached.
   *
   * @return the validation error, for further assertions on its message
   */
  private static LLMObs.Feedback.ValidationError assertRejected(
      String expectedCode, LLMObs.Feedback.Builder builder) {
    LLMObs.Feedback feedback = assertDoesNotThrow(builder::build);
    LLMObs.Feedback.ValidationError error = feedback.validate();
    assertNotNull(error, "expected the feedback to be rejected with " + expectedCode);
    assertEquals(expectedCode, error.getCode(), error.getMessage());
    return error;
  }

  // --- deferred validation ---

  @Test
  void testAValidFeedbackHasNoValidationError() {
    assertNull(validBuilder().build().validate());
  }

  @Test
  void testTheFirstProblemWinsOverLaterOnes() {
    // The empty span id is reported even though the label and the value are missing too.
    assertRejected("invalid_span_id", LLMObs.Feedback.builder().spanId("").label("thumbs.up"));
  }

  // --- targets ---

  @Test
  void testMissingTargetIsRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected(
            "invalid_target_count",
            LLMObs.Feedback.builder()
                .label("thumbs")
                .booleanValue(true)
                .submitter("user-123", null));
    assertTrue(error.getMessage().contains("feedbackJoinKey"), error.getMessage());
  }

  @Test
  void testEachTargetTypeIsCarriedWithItsWireKey() {
    LLMObs.Feedback bySpanId = validBuilder().build();
    assertEquals(LLMObs.Feedback.TargetType.SPAN_ID, bySpanId.getTargetType());
    assertEquals("span_id", bySpanId.getTargetType().getWireKey());
    assertEquals("123", bySpanId.getTargetValue());

    LLMObs.Feedback byTraceId =
        LLMObs.Feedback.builder()
            .traceId("abc")
            .label("thumbs")
            .booleanValue(true)
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.TargetType.TRACE_ID, byTraceId.getTargetType());
    assertEquals("trace_id", byTraceId.getTargetType().getWireKey());
    assertEquals("abc", byTraceId.getTargetValue());

    LLMObs.Feedback bySessionId =
        LLMObs.Feedback.builder()
            .sessionId("session-2")
            .label("thumbs")
            .booleanValue(true)
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.TargetType.SESSION_ID, bySessionId.getTargetType());
    assertEquals("session_id", bySessionId.getTargetType().getWireKey());
    assertEquals("session-2", bySessionId.getTargetValue());

    LLMObs.Feedback byJoinKey =
        LLMObs.Feedback.builder()
            .feedbackJoinKey("incident-123")
            .label("user_comment")
            .textValue("missed the customer impact")
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.TargetType.FEEDBACK_JOIN_KEY, byJoinKey.getTargetType());
    assertEquals("feedback_join_key", byJoinKey.getTargetType().getWireKey());
    assertEquals("incident-123", byJoinKey.getTargetValue());
  }

  @Test
  void testSpanTargetsItsSpanId() {
    LLMObsSpan span = mock(LLMObsSpan.class);
    when(span.getSpanId()).thenReturn(4242L);

    LLMObs.Feedback feedback =
        LLMObs.Feedback.builder()
            .span(span)
            .label("thumbs")
            .booleanValue(true)
            .submitter("user-123", null)
            .build();

    assertEquals(LLMObs.Feedback.TargetType.SPAN_ID, feedback.getTargetType());
    assertEquals("4242", feedback.getTargetValue());
  }

  @Test
  void testNullSpanIsRejected() {
    assertRejected("invalid_span", LLMObs.Feedback.builder().span((LLMObsSpan) null));
  }

  @Test
  void testTwoDifferentTargetsAreRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected(
            "invalid_target_count", LLMObs.Feedback.builder().spanId("123").sessionId("session-2"));
    assertTrue(error.getMessage().contains("span_id"), error.getMessage());
  }

  @Test
  void testSameTargetSetTwiceIsRejected() {
    assertRejected("invalid_target_count", LLMObs.Feedback.builder().spanId("123").spanId("456"));
  }

  @Test
  void testEmptyAndNullTargetValuesAreRejected() {
    assertRejected("invalid_span_id", LLMObs.Feedback.builder().spanId(""));
    assertRejected("invalid_span_id", LLMObs.Feedback.builder().spanId(null));
    assertRejected("invalid_trace_id", LLMObs.Feedback.builder().traceId(""));
    assertRejected("invalid_session_id", LLMObs.Feedback.builder().sessionId(""));
    assertRejected("invalid_feedback_join_key", LLMObs.Feedback.builder().feedbackJoinKey(""));
  }

  // --- label ---

  @Test
  void testMissingAndEmptyLabelsAreRejected() {
    assertRejected(
        "invalid_metric_label",
        LLMObs.Feedback.builder().spanId("123").booleanValue(true).submitter("user-123", null));

    assertRejected(
        "invalid_metric_label",
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("")
            .booleanValue(true)
            .submitter("user-123", null));
  }

  @Test
  void testDottedLabelIsRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected("invalid_label_value", validBuilder().label("thumbs.up"));
    assertEquals("label value must not contain a '.'", error.getMessage());
  }

  // --- values ---

  @Test
  void testMissingValueIsRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected(
            "invalid_metric_type",
            LLMObs.Feedback.builder().spanId("123").label("thumbs").submitter("user-123", null));
    assertTrue(error.getMessage().contains("booleanValue"), error.getMessage());
  }

  @Test
  void testEachMetricTypeCarriesItsValue() {
    LLMObs.Feedback categorical =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("satisfaction")
            .categoricalValue("satisfied")
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.MetricType.CATEGORICAL, categorical.getMetricType());
    assertEquals("categorical", categorical.getMetricType().toString());
    assertEquals("satisfied", categorical.getValue());

    LLMObs.Feedback score =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("rating")
            .scoreValue(0.75)
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.MetricType.SCORE, score.getMetricType());
    assertEquals("score", score.getMetricType().toString());
    assertEquals(0.75, score.getValue());

    LLMObs.Feedback bool = validBuilder().build();
    assertEquals(LLMObs.Feedback.MetricType.BOOLEAN, bool.getMetricType());
    assertEquals("boolean", bool.getMetricType().toString());
    assertEquals(true, bool.getValue());

    Map<String, Object> details = Collections.singletonMap("missing", "customer impact");
    LLMObs.Feedback json =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("details")
            .jsonValue(details)
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.MetricType.JSON, json.getMetricType());
    assertEquals("json", json.getMetricType().toString());
    assertEquals(details, json.getValue());

    LLMObs.Feedback text =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("user_comment")
            .textValue("missed the customer impact")
            .submitter("user-123", null)
            .build();
    assertEquals(LLMObs.Feedback.MetricType.TEXT, text.getMetricType());
    assertEquals("text", text.getMetricType().toString());
    assertEquals("missed the customer impact", text.getValue());
  }

  @Test
  void testTwoValuesAreRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected(
            "invalid_metric_type",
            LLMObs.Feedback.builder().booleanValue(true).textValue("also this"));
    assertTrue(error.getMessage().contains("boolean"), error.getMessage());
  }

  @Test
  void testSameValueKindSetTwiceIsRejected() {
    assertRejected(
        "invalid_metric_type", LLMObs.Feedback.builder().scoreValue(0.1).scoreValue(0.2));
  }

  @Test
  void testNullValuesAreRejected() {
    assertRejected("invalid_metric_value", LLMObs.Feedback.builder().categoricalValue(null));
    assertRejected("invalid_metric_value", LLMObs.Feedback.builder().textValue(null));
    assertRejected("invalid_metric_value", LLMObs.Feedback.builder().jsonValue(null));
  }

  // --- submitter ---

  @Test
  void testMissingSubmitterIsRejected() {
    LLMObs.Feedback.ValidationError error =
        assertRejected(
            "invalid_submitter",
            LLMObs.Feedback.builder().spanId("123").label("thumbs").booleanValue(true));
    assertTrue(error.getMessage().contains("submitter"), error.getMessage());
  }

  @Test
  void testSubmitterIdIsRequired() {
    assertRejected("invalid_submitter", validBuilder().submitter("", "end_user"));
    assertRejected("invalid_submitter", validBuilder().submitter(null, "end_user"));
    assertRejected(
        "invalid_submitter", validBuilder().submitter(new LLMObs.Feedback.Submitter("", null)));
  }

  @Test
  void testSubmitterTypeIsOptional() {
    LLMObs.Feedback withType = validBuilder().build();
    assertEquals("user-123", withType.getSubmitter().getId());
    assertEquals("end_user", withType.getSubmitter().getType());

    LLMObs.Feedback withoutType =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("thumbs")
            .booleanValue(true)
            .submitter("user-123", null)
            .build();
    assertEquals("user-123", withoutType.getSubmitter().getId());
    assertNull(withoutType.getSubmitter().getType());
  }

  @Test
  void testSubmitterCanBeSuppliedAsAnInstance() {
    LLMObs.Feedback.Submitter submitter = new LLMObs.Feedback.Submitter("user-123", "end_user");

    LLMObs.Feedback feedback =
        LLMObs.Feedback.builder()
            .spanId("123")
            .label("thumbs")
            .booleanValue(true)
            .submitter(submitter)
            .build();

    assertEquals("user-123", feedback.getSubmitter().getId());
    assertEquals("end_user", feedback.getSubmitter().getType());
  }

  // --- optional fields ---

  @Test
  void testAssessmentAndReasoningDefaultToAbsent() {
    LLMObs.Feedback feedback = validBuilder().build();

    assertNull(feedback.getAssessment());
    assertNull(feedback.getReasoning());
    assertNull(feedback.getMlApp());
    assertNull(feedback.getTags());
  }

  @Test
  void testAssessmentAndReasoningAreCarried() {
    LLMObs.Feedback feedback =
        validBuilder()
            .assessment(LLMObs.Feedback.Assessment.FAIL)
            .reasoning("missed the customer impact")
            .mlApp("incident-agent")
            .build();

    assertEquals(LLMObs.Feedback.Assessment.FAIL, feedback.getAssessment());
    assertEquals("fail", feedback.getAssessment().toString());
    assertEquals("missed the customer impact", feedback.getReasoning());
    assertEquals("incident-agent", feedback.getMlApp());
    assertEquals("pass", LLMObs.Feedback.Assessment.PASS.toString());
  }

  // --- timestamp ---

  @Test
  void testTimestampDefaultsToNow() {
    long before = System.currentTimeMillis();
    LLMObs.Feedback feedback = validBuilder().build();
    long after = System.currentTimeMillis();

    assertTrue(
        feedback.getTimestampMs() >= before && feedback.getTimestampMs() <= after,
        "expected " + feedback.getTimestampMs() + " within [" + before + ", " + after + "]");
  }

  @Test
  void testExplicitTimestampIsPreserved() {
    assertEquals(1234L, validBuilder().timestampMs(1234L).build().getTimestampMs());
  }

  @Test
  void testNegativeTimestampIsRejected() {
    assertRejected("invalid_timestamp", validBuilder().timestampMs(-1L));
  }

  @Test
  void testBuilderIsReusableAndDoesNotFreezeTheDefaultTimestamp() throws Exception {
    LLMObs.Feedback.Builder builder = validBuilder();

    LLMObs.Feedback first = builder.build();
    Thread.sleep(2);
    LLMObs.Feedback second = builder.build();

    assertNotEquals(first.getTimestampMs(), second.getTimestampMs());
  }

  // --- tags ---

  @Test
  void testTagsAreCopiedAndExposedAsUnmodifiable() {
    Map<String, Object> source = new HashMap<>();
    source.put("source", "web-ui");

    LLMObs.Feedback feedback = validBuilder().tags(source).build();
    source.put("added-after", "should not appear");

    assertEquals(Collections.singletonMap("source", "web-ui"), feedback.getTags());
    assertThrows(UnsupportedOperationException.class, () -> feedback.getTags().put("nope", "nope"));
  }

  @Test
  void testSingleTagsAccumulate() {
    LLMObs.Feedback feedback = validBuilder().tag("source", "web-ui").tag("revision", "2").build();

    Map<String, Object> expected = new HashMap<>();
    expected.put("source", "web-ui");
    expected.put("revision", "2");
    assertEquals(expected, feedback.getTags());
  }

  @Test
  void testTagsAddedAfterBuildDoNotLeakIntoTheBuiltFeedback() {
    LLMObs.Feedback.Builder builder = validBuilder().tag("source", "web-ui");

    LLMObs.Feedback feedback = builder.build();
    builder.tag("revision", "2");

    assertEquals(Collections.singletonMap("source", "web-ui"), feedback.getTags());
  }

  @Test
  void testNullTagsAreAccepted() {
    assertNull(validBuilder().tags(null).build().getTags());
  }

  // --- submission ---

  @Test
  void testDefaultNoOpFeedbackProcessorBehavior() {
    assertDoesNotThrow(
        () -> {
          LLMObs.submitFeedback(validBuilder().build());
          LLMObs.submitFeedback(null);
        });
  }

  @Test
  void testAnInvalidFeedbackIsSilentWhenNoProcessorIsInstalled() {
    // Without the agent, or with LLM Observability disabled, submitting garbage must not break the
    // host application. The real processor is the one that rejects it.
    assertDoesNotThrow(
        () -> LLMObs.submitFeedback(LLMObs.Feedback.builder().label("thumbs.up").build()));
  }

  @Test
  void testSubmitFeedbackDelegatesToTheProcessor() throws Exception {
    LLMObs.LLMObsFeedbackProcessor mockProcessor = mock(LLMObs.LLMObsFeedbackProcessor.class);
    setStaticField("FEEDBACK_PROCESSOR", mockProcessor);

    LLMObs.Feedback feedback =
        LLMObs.Feedback.builder()
            .span(NoOpLLMObsSpan.INSTANCE)
            .label("thumbs")
            .booleanValue(false)
            .submitter("user-123", "end_user")
            .assessment(LLMObs.Feedback.Assessment.FAIL)
            .build();

    LLMObs.submitFeedback(feedback);

    verify(mockProcessor).submitFeedback(feedback);
  }

  private static void setStaticField(String fieldName, Object value) throws Exception {
    Field field = LLMObs.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object getStaticField(String fieldName) throws Exception {
    Field field = LLMObs.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(null);
  }
}

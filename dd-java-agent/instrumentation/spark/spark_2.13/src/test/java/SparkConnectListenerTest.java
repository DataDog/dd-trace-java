import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.spark.DatadogSpark213Listener;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.JobFailed;
import org.apache.spark.scheduler.JobSucceeded$;
import org.apache.spark.scheduler.SparkListenerApplicationEnd;
import org.apache.spark.scheduler.SparkListenerApplicationStart;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart;
import org.junit.jupiter.api.Test;
import scala.Option;
import scala.collection.immutable.Map$;
import scala.collection.immutable.Nil$;
import scala.collection.immutable.Seq;
import scala.collection.immutable.Set$;

/**
 * Tests that Spark Connect jobs emit one spark.application root span per session (independent
 * traces), with SQL/job spans nested directly under the per-session app span.
 */
class SparkConnectListenerTest extends AbstractInstrumentationTest {

  @Test
  void sqlSpanIsNestedUnderSessionSpanNotApplicationSpan() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");

    listener.onApplicationStart(appStartEvent(1000L));
    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-abc", 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1500L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1500L));
    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")));

    List<DDSpan> trace = writer.get(0);
    DDSpan appSpan = findSpan(trace, "spark.application");
    assertNotNull(appSpan);
    assertEquals("session-abc", appSpan.getTag("session_id"));
    assertEquals(true, appSpan.getTag("spark.connect.server"));
  }

  @Test
  void separateSessionsGetDistinctSessionSpans() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");

    listener.onApplicationStart(appStartEvent(1000L));

    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-1", 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1400L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1400L));

    listener.onOtherEvent(sqlStartEvent(2L, 1500L));
    listener.onJobStart(connectJobStartEvent(2, 1600L, "session-2", 2L));
    listener.onJobEnd(new SparkListenerJobEnd(2, 1800L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(2L, 1800L));

    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")),
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")));

    DDSpan appSpan0 =
        writer.get(0).stream()
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No spark.application span in trace 0"));
    DDSpan appSpan1 =
        writer.get(1).stream()
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No spark.application span in trace 1"));

    List<String> sessionIds =
        Arrays.asList(
            (String) appSpan0.getTag("session_id"), (String) appSpan1.getTag("session_id"));
    assertTrue(sessionIds.contains("session-1"));
    assertTrue(sessionIds.contains("session-2"));
  }

  @Test
  void sameSessionReusesExistingSessionSpan() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");

    listener.onApplicationStart(appStartEvent(1000L));

    // Two SQL queries from the same session
    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-abc", 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1400L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1400L));

    listener.onOtherEvent(sqlStartEvent(2L, 1500L));
    listener.onJobStart(connectJobStartEvent(2, 1600L, "session-abc", 2L));
    listener.onJobEnd(new SparkListenerJobEnd(2, 1800L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(2L, 1800L));

    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")));

    List<DDSpan> trace = writer.get(0);
    long appSpanCount =
        trace.stream()
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .count();
    assertEquals(1, appSpanCount);

    DDSpan appSpan = findSpan(trace, "spark.application");
    assertNotNull(appSpan);
    assertEquals("session-abc", appSpan.getTag("session_id"));
  }

  @Test
  void nonConnectJobOnConnectServerIsTracedSeparately() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");

    listener.onApplicationStart(appStartEvent(1000L));

    // Connect job for session-abc
    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-abc", 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1400L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1400L));

    // Non-Connect job (no spark.jobTags)
    listener.onOtherEvent(sqlStartEvent(2L, 1500L));
    listener.onJobStart(plainJobStartEvent(2, 1600L, 2L));
    listener.onJobEnd(new SparkListenerJobEnd(2, 1800L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(2L, 1800L));

    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    // Expect 2 traces: one per-session app span, one global app span
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")),
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark"),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark")));

    // One trace has session_id (Connect), one does not (global)
    DDSpan sessionAppSpan =
        writer.stream()
            .flatMap(List::stream)
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .filter(s -> s.getTag("session_id") != null)
            .findFirst()
            .orElse(null);
    DDSpan globalAppSpan =
        writer.stream()
            .flatMap(List::stream)
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .filter(s -> s.getTag("session_id") == null)
            .findFirst()
            .orElse(null);

    assertNotNull(sessionAppSpan, "Expected a per-session application span");
    assertNotNull(globalAppSpan, "Expected a global application span");
    assertEquals("session-abc", sessionAppSpan.getTag("session_id"));
    assertEquals(true, sessionAppSpan.getTag("spark.connect.server"));
  }

  @Test
  void connectSessionJobFailureIsAttributedToSession() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");

    listener.onApplicationStart(appStartEvent(1000L));

    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-fail", 1L));
    listener.onJobEnd(
        new SparkListenerJobEnd(1, 1500L, new JobFailed(new RuntimeException("job error"))));
    listener.onOtherEvent(sqlEndEvent(1L, 1500L));

    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().operationName("spark.application").type("spark").error(true),
            span().operationName("spark.sql").type("spark"),
            span().operationName("spark.job").type("spark").error(true)));

    List<DDSpan> trace = writer.get(0);
    DDSpan appSpan = findSpan(trace, "spark.application");
    assertNotNull(appSpan);
    assertEquals("session-fail", appSpan.getTag("session_id"));
    assertTrue(appSpan.isError(), "Per-session app span should have error set");
  }

  @Test
  void sessionCapOverflowFallsBackToGlobalSpan() throws Exception {
    // Override maxCollectionSize() to 1 so a second session overflows without creating 5000
    // entries.
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0") {
          @Override
          protected int maxCollectionSize() {
            return 1;
          }
        };
    listener.onApplicationStart(appStartEvent(1000L));

    // First session fills the cap.
    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(connectJobStartEvent(1, 1200L, "session-at-cap", 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1400L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1400L));

    // Second session overflows — must fall back to the global span, not create an orphan.
    listener.onOtherEvent(sqlStartEvent(2L, 1500L));
    listener.onJobStart(connectJobStartEvent(2, 1600L, "overflow-session", 2L));
    listener.onJobEnd(new SparkListenerJobEnd(2, 1800L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(2L, 1800L));

    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    // No per-session trace for the overflow session.
    boolean hasOverflowSession =
        writer.stream()
            .flatMap(List::stream)
            .anyMatch(s -> "overflow-session".equals(s.getTag("session_id")));
    assertFalse(
        hasOverflowSession, "Overflow session must not create an independent per-session trace");

    // The overflow session's spans are parented under the global spark.application (no session_id).
    DDSpan globalAppSpan =
        writer.stream()
            .flatMap(List::stream)
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .filter(s -> s.getTag("session_id") == null)
            .findFirst()
            .orElse(null);
    assertNotNull(globalAppSpan, "Overflow session must fall back to the global application span");
  }

  @Test
  void emptySessionIdIsRejected() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");
    listener.onApplicationStart(appStartEvent(1000L));

    // spark.jobTags with empty suffix after the prefix should not produce a session span.
    Properties props = new Properties();
    props.setProperty("spark.sql.execution.id", "1");
    props.setProperty("spark.jobTags", "spark-connect-session-");
    @SuppressWarnings("unchecked")
    Seq<StageInfo> emptyStages = (Seq<StageInfo>) (Object) Nil$.MODULE$;
    listener.onOtherEvent(sqlStartEvent(1L, 1100L));
    listener.onJobStart(new SparkListenerJobStart(1, 1200L, emptyStages, props));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1400L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1400L));
    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    // Should produce the global spark.application trace (no per-session span).
    assertEquals(1, writer.size(), "Expected exactly one trace (global app span)");
    DDSpan appSpan = findSpan(writer.get(0), "spark.application");
    assertNotNull(appSpan);
    assertNull(appSpan.getTag("session_id"), "Empty session id must not be stored");
  }

  @Test
  void connectJobSuccessDoesNotClearGlobalSqlFailure() throws Exception {
    DatadogSpark213Listener listener =
        new DatadogSpark213Listener(new SparkConf(), "test_app_id", "3.5.0");
    listener.onApplicationStart(appStartEvent(1000L));

    // A non-Connect job succeeds first — this initializes the global application span.
    listener.onOtherEvent(sqlStartEvent(1L, 1050L));
    listener.onJobStart(plainJobStartEvent(1, 1100L, 1L));
    listener.onJobEnd(new SparkListenerJobEnd(1, 1200L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(1L, 1200L));

    // A SQL analysis failure fires after the non-Connect job completes.
    listener.onSqlFailure(new RuntimeException("analysis error"));

    // A Connect job from a different session then succeeds — must not clear the global SQL failure.
    listener.onOtherEvent(sqlStartEvent(2L, 1300L));
    listener.onJobStart(connectJobStartEvent(2, 1400L, "session-ok", 2L));
    listener.onJobEnd(new SparkListenerJobEnd(2, 1500L, JobSucceeded$.MODULE$));
    listener.onOtherEvent(sqlEndEvent(2L, 1500L));
    listener.onApplicationEnd(new SparkListenerApplicationEnd(2000L));

    // The per-session span must not be errored (the Connect job succeeded).
    DDSpan sessionAppSpan =
        writer.stream()
            .flatMap(List::stream)
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .filter(s -> "session-ok".equals(s.getTag("session_id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No per-session app span found"));
    assertFalse(
        sessionAppSpan.isError(), "Connect session span must not carry the global SQL error");

    // The global span must still reflect the SQL failure.
    DDSpan globalAppSpan =
        writer.stream()
            .flatMap(List::stream)
            .filter(s -> "spark.application".equals(s.getOperationName().toString()))
            .filter(s -> s.getTag("session_id") == null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No global app span found"));
    assertTrue(globalAppSpan.isError(), "Global app span must still reflect the SQL failure");
  }

  // region Helpers

  private static DDSpan findSpan(List<DDSpan> trace, String operationName) {
    return trace.stream()
        .filter(s -> operationName.equals(s.getOperationName().toString()))
        .findFirst()
        .orElse(null);
  }

  private static SparkListenerApplicationStart appStartEvent(long time) {
    return new SparkListenerApplicationStart(
        "test_app",
        Option.apply("test_app_id"),
        time,
        "test_user",
        Option.apply("1"),
        Option.empty(),
        Option.empty());
  }

  private static SparkListenerJobStart connectJobStartEvent(
      int jobId, long time, String sessionId, long sqlExecutionId) {
    Properties props = new Properties();
    props.setProperty("spark.sql.execution.id", String.valueOf(sqlExecutionId));
    props.setProperty("spark.jobTags", "spark-connect-session-" + sessionId);

    @SuppressWarnings("unchecked")
    Seq<StageInfo> emptyStages = (Seq<StageInfo>) (Object) Nil$.MODULE$;
    return new SparkListenerJobStart(jobId, time, emptyStages, props);
  }

  private static SparkListenerJobStart plainJobStartEvent(
      int jobId, long time, long sqlExecutionId) {
    Properties props = new Properties();
    props.setProperty("spark.sql.execution.id", String.valueOf(sqlExecutionId));

    @SuppressWarnings("unchecked")
    Seq<StageInfo> emptyStages = (Seq<StageInfo>) (Object) Nil$.MODULE$;
    return new SparkListenerJobStart(jobId, time, emptyStages, props);
  }

  /**
   * Constructs {@link SparkListenerSQLExecutionStart} using reflection to handle constructor
   * differences across Spark versions (3.2 has 6 args, 3.3 has 7 args, 3.4 adds rootExecutionId,
   * 3.5 adds jobTags).
   */
  @SuppressWarnings("unchecked")
  private static SparkListenerSQLExecutionStart sqlStartEvent(long executionId, long time)
      throws ReflectiveOperationException {
    SparkPlanInfo planInfo =
        new SparkPlanInfo(
            "scan",
            "scan",
            (Seq<SparkPlanInfo>) (Object) Nil$.MODULE$,
            Map$.MODULE$.empty(),
            (Seq<SQLMetricInfo>) (Object) Nil$.MODULE$);

    Constructor<?>[] ctors = SparkListenerSQLExecutionStart.class.getConstructors();
    Arrays.sort(ctors, Comparator.comparingInt(Constructor::getParameterCount));

    for (Constructor<?> ctor : ctors) {
      int n = ctor.getParameterCount();
      if (n == 6) {
        // Spark 3.2: (Long, String, String, String, SparkPlanInfo, Long)
        return (SparkListenerSQLExecutionStart)
            ctor.newInstance(executionId, "SELECT 1", "", "", planInfo, time);
      } else if (n == 7) {
        // Spark 3.3: (Long, String, String, String, SparkPlanInfo, Long, Map)
        return (SparkListenerSQLExecutionStart)
            ctor.newInstance(executionId, "SELECT 1", "", "", planInfo, time, Map$.MODULE$.empty());
      } else if (n == 8) {
        // Spark 3.4: (Long, Option, String, String, String, SparkPlanInfo, Long, Map)
        return (SparkListenerSQLExecutionStart)
            ctor.newInstance(
                executionId,
                Option.empty(),
                "SELECT 1",
                "",
                "",
                planInfo,
                time,
                Map$.MODULE$.empty());
      } else if (n == 9) {
        // Spark 3.5+: (Long, Option, String, String, String, SparkPlanInfo, Long, Map, Set)
        return (SparkListenerSQLExecutionStart)
            ctor.newInstance(
                executionId,
                Option.empty(),
                "SELECT 1",
                "",
                "",
                planInfo,
                time,
                Map$.MODULE$.empty(),
                Set$.MODULE$.empty());
      }
    }
    throw new IllegalStateException(
        "No compatible SparkListenerSQLExecutionStart constructor found in "
            + Arrays.toString(ctors));
  }

  private static SparkListenerSQLExecutionEnd sqlEndEvent(long executionId, long time)
      throws ReflectiveOperationException {
    Constructor<?>[] ctors = SparkListenerSQLExecutionEnd.class.getConstructors();
    Arrays.sort(ctors, Comparator.comparingInt(Constructor::getParameterCount));

    for (Constructor<?> ctor : ctors) {
      int n = ctor.getParameterCount();
      if (n == 2) {
        // Spark 3.2: (Long, Long)
        return (SparkListenerSQLExecutionEnd) ctor.newInstance(executionId, time);
      } else if (n == 3) {
        // Spark 3.4+: (Long, Long, Option)
        return (SparkListenerSQLExecutionEnd) ctor.newInstance(executionId, time, Option.empty());
      }
    }
    throw new IllegalStateException(
        "No compatible SparkListenerSQLExecutionEnd constructor found in "
            + Arrays.toString(ctors));
  }

  // endregion
}

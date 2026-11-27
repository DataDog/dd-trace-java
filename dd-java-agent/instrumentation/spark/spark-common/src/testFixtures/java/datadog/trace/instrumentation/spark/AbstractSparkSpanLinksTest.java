package datadog.trace.instrumentation.spark;

import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_OPENLINEAGE_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_SPARK_SPAN_LINKS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.spark.SparkConf;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.JobSucceeded$;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerApplicationEnd;
import org.apache.spark.scheduler.SparkListenerApplicationStart;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.util.AccumulatorV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Versioned behavior coverage for independent Spark application traces. */
@WithConfig(key = DATA_JOBS_ENABLED, value = "true")
@WithConfig(key = DATA_JOBS_OPENLINEAGE_ENABLED, value = "true")
@WithConfig(key = "integration.spark.enabled", value = "true")
@WithConfig(key = "integration.spark-openlineage.enabled", value = "true")
public abstract class AbstractSparkSpanLinksTest extends AbstractInstrumentationTest {
  private static final String PARENT_RUN_ID = "ad3b6baa-8d88-3b38-8dbe-f06232249a84";
  private static final String ROOT_PARENT_RUN_ID = "01964820-5280-7674-b04e-82fbed085f39";
  private SparkSession currentSession;

  @BeforeEach
  void resetSparkState() {
    AbstractDatadogSparkListener.listener = null;
  }

  @AfterEach
  void stopSparkAndClearOpenLineageStore() {
    if (currentSession != null) {
      currentSession.stop();
      currentSession = null;
    }
    InstanceStore.of(SparkListenerInterface.class).remove("openLineageListener");
    InstanceStore.of(SparkConf.class).remove("openLineageSparkConf");
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
  }

  @Test
  void flagsUnsetKeepLegacyOpenLineageTopology() throws Exception {
    assertOpenLineageMode(false);
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "false")
  @WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "false")
  void bothFlagsDisabledKeepLegacyOpenLineageTopology() throws Exception {
    assertOpenLineageMode(false);
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  @WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "false")
  void dedicatedFlagCreatesIndependentApplicationTrace() throws Exception {
    assertOpenLineageMode(true);
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "false")
  @WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "true")
  void experimentalFlagCreatesIndependentApplicationTrace() throws Exception {
    assertOpenLineageMode(true);
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  @WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "true")
  void bothFlagsCreateOneIndependentApplicationTraceAndLink() throws Exception {
    assertOpenLineageMode(true);
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  @WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "false")
  void listenerKeepsItsConstructionTimeMode() throws Exception {
    SparkSession spark = newDatabricksSession("fixed-listener-mode");
    injectSysConfig(DATA_JOBS_SPARK_SPAN_LINKS, "false");
    injectSysConfig(DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, "false");

    setDatabricksTask(spark, "1234", "5678", "9012", null);
    TestSparkComputation.generateTestSparkComputation(spark);
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    DDSpan job = onlySpan(trace, "spark.job");
    DatabricksParentContext external = new DatabricksParentContext("1234", "5678", "9012", 0);

    assertEquals(0, application.getParentId());
    assertEquals(application.getSpanId(), job.getParentId());
    assertLink(job, external.getTraceId(), external.getSpanId());
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void keepsSqlJobsInsideTheApplicationTrace() throws Exception {
    SparkSession spark = newSession("sql-topology", false, false);
    spark.range(0, 10).coalesce(1).count();
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    DDSpan sql = onlySpan(trace, "spark.sql");
    DDSpan job = onlySpan(trace, "spark.job");
    List<DDSpan> stages = spans(trace, "spark.stage");

    assertEquals(0, application.getParentId());
    assertEquals(application.getSpanId(), sql.getParentId());
    assertEquals(sql.getSpanId(), job.getParentId());
    assertFalse(stages.isEmpty());
    assertTrue(stages.stream().allMatch(s -> s.getParentId() == job.getSpanId()));
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void ignoresAmbientActiveSpan() throws Exception {
    AgentSpan ambient = startSpan("test", "ambient");
    try (ContextScope ignored = activateSpan(ambient)) {
      MinimalSparkListener listener =
          new MinimalSparkListener(new SparkConf(false), "ambient-context", "test");
      listener.finishApplication(System.currentTimeMillis(), null, 0, null);
    } finally {
      ambient.finish();
    }

    writer.waitForTraces(2);
    List<DDSpan> allSpans = allReportedSpans();
    DDSpan application = onlySpan(allSpans, "spark.application");
    DDSpan ambientSpan = onlySpan(allSpans, "ambient");

    assertEquals(0, application.getParentId());
    assertNotEquals(ambientSpan.getTraceId(), application.getTraceId());
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void forwardsOpenLineageWithApplicationTraceId() throws Exception {
    RecordingSparkListener openLineageListener = new RecordingSparkListener();
    SparkConf forwardedConf = new SparkConf(false);
    InstanceStore.of(SparkListenerInterface.class).put("openLineageListener", openLineageListener);
    InstanceStore.of(SparkConf.class).put("openLineageSparkConf", forwardedConf);

    SparkSession spark = newSession("openlineage-forwarding", false, true);
    OpenlineageParentContext external =
        OpenlineageParentContext.from(spark.sparkContext().getConf())
            .orElseThrow(AssertionError::new);
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    List<String> runTags =
        Arrays.asList(Pattern.compile(";").split(forwardedConf.get("spark.openlineage.run.tags")));

    assertEquals(1, openLineageListener.applicationStarts.get());
    assertEquals(1, openLineageListener.applicationEnds.get());
    assertTrue(runTags.contains("_dd.trace_id:" + application.getTraceId()));
    assertTrue(runTags.contains("_dd.ol_intake.emit_spans:false"));
    assertLink(application, external.getTraceId(), external.getSpanId());
    assertEquals(1, trace.size(), "the forwarded listener must not duplicate tracer spans");
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void keepsSharedDatabricksTaskLinksIsolated() throws Exception {
    SparkSession spark = newDatabricksSession("shared-databricks-driver");

    setDatabricksTask(spark, "1234", "5678", "9012", null);
    TestSparkComputation.generateTestSparkComputation(spark);
    setDatabricksTask(spark, "1234", "5678", "3456", null);
    TestSparkComputation.generateTestSparkComputation(spark);
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    List<DDSpan> jobs = spans(trace, "spark.job");
    assertEquals(2, jobs.size());
    assertTrue(application.getLinks().isEmpty());

    assertDatabricksJobLink(jobs, "9012", new DatabricksParentContext("1234", "5678", "9012", 0));
    assertDatabricksJobLink(jobs, "3456", new DatabricksParentContext("1234", "5678", "3456", 0));
    assertTrue(jobs.stream().allMatch(job -> job.getParentId() == application.getSpanId()));
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void linksRepairedDatabricksAttemptWithoutReusingItsTraceId() throws Exception {
    SparkSession spark = newDatabricksSession("repaired-databricks-run");
    setDatabricksTask(spark, "1234", "5678", "3456", encodedRepairAttempt(1));
    TestSparkComputation.generateTestSparkComputation(spark);
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    DDSpan job = onlySpan(trace, "spark.job");
    DatabricksParentContext repaired = new DatabricksParentContext("1234", "5678", "3456", 1);

    assertNotEquals(repaired.getTraceId(), application.getTraceId());
    assertEquals(application.getSpanId(), job.getParentId());
    assertLink(job, repaired.getTraceId(), repaired.getSpanId());
  }

  @Test
  @WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
  void applicationEndPreservesCallbackDrivenJobFinalization() throws Exception {
    SparkSession spark = newSession("callback-driven-finalization", false, false);
    AbstractDatadogSparkListener listener = AbstractDatadogSparkListener.listener;
    assertNotNull(listener);

    listener.onJobStart(jobStart(9001));
    stopCurrentSession();
    assertEquals(0, writer.size());
    listener.onJobEnd(
        new SparkListenerJobEnd(9001, System.currentTimeMillis(), JobSucceeded$.MODULE$));

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    DDSpan job = onlySpan(trace, "spark.job");
    assertEquals(application.getSpanId(), job.getParentId());
    assertEquals(2, trace.size());
  }

  private void assertOpenLineageMode(boolean enabled) throws Exception {
    SparkSession spark = newSession("flag-mode-" + enabled, false, true);
    OpenlineageParentContext external =
        OpenlineageParentContext.from(spark.sparkContext().getConf())
            .orElseThrow(AssertionError::new);
    TestSparkComputation.generateTestSparkComputation(spark);
    stopCurrentSession();

    List<DDSpan> trace = waitForSingleTrace();
    DDSpan application = onlySpan(trace, "spark.application");
    DDSpan job = onlySpan(trace, "spark.job");
    List<DDSpan> stages = spans(trace, "spark.stage");

    assertEquals(application.getSpanId(), job.getParentId());
    assertFalse(stages.isEmpty());
    assertTrue(stages.stream().allMatch(s -> s.getParentId() == job.getSpanId()));
    assertTrue(trace.stream().allMatch(s -> s.getTraceId().equals(application.getTraceId())));
    if (enabled) {
      assertEquals(0, application.getParentId());
      assertNotEquals(external.getTraceId(), application.getTraceId());
      assertLink(application, external.getTraceId(), external.getSpanId());
    } else {
      assertEquals(external.getSpanId(), application.getParentId());
      assertEquals(external.getTraceId(), application.getTraceId());
      assertTrue(application.getLinks().isEmpty());
    }
  }

  private SparkSession newDatabricksSession(String appName) {
    return newSession(appName, true, false);
  }

  private SparkSession newSession(String appName, boolean databricks, boolean openLineage) {
    SparkSession.Builder builder =
        SparkSession.builder()
            .master("local[2]")
            .appName(appName)
            .config("spark.default.parallelism", "2")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.ui.enabled", "false");
    if (databricks) {
      builder
          .config("spark.databricks.sparkContextId", "test-context")
          .config("spark.databricks.clusterUsageTags.clusterName", "job-1234-run-5678-Job_cluster");
    }
    if (openLineage) {
      builder
          .config("spark.openlineage.parentRunId", PARENT_RUN_ID)
          .config("spark.openlineage.parentJobNamespace", "default")
          .config("spark.openlineage.parentJobName", "parent-job")
          .config("spark.openlineage.rootParentRunId", ROOT_PARENT_RUN_ID)
          .config("spark.openlineage.rootParentJobNamespace", "default")
          .config("spark.openlineage.rootParentJobName", "root-job");
    }
    currentSession = builder.getOrCreate();
    return currentSession;
  }

  private void stopCurrentSession() {
    SparkSession spark = currentSession;
    currentSession = null;
    spark.stop();
  }

  private List<DDSpan> waitForSingleTrace() throws Exception {
    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    return writer.get(0);
  }

  private List<DDSpan> allReportedSpans() {
    return writer.stream().flatMap(List::stream).collect(Collectors.toList());
  }

  private static List<DDSpan> spans(List<DDSpan> spans, String operationName) {
    return spans.stream()
        .filter(span -> operationName.contentEquals(span.getOperationName()))
        .collect(Collectors.toList());
  }

  private static DDSpan onlySpan(List<DDSpan> spans, String operationName) {
    List<DDSpan> matches = spans(spans, operationName);
    assertEquals(1, matches.size(), "expected exactly one " + operationName + " span");
    return matches.get(0);
  }

  private static void assertLink(DDSpan span, DDTraceId traceId, long spanId) {
    assertEquals(1, span.getLinks().size());
    AgentSpanLink link = span.getLinks().get(0);
    assertEquals(traceId, link.traceId());
    assertEquals(spanId, link.spanId());
  }

  private static void assertDatabricksJobLink(
      List<DDSpan> jobs, String taskRunId, DatabricksParentContext expected) {
    DDSpan job =
        jobs.stream()
            .filter(span -> taskRunId.equals(span.getTag("databricks_task_run_id")))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("missing job span for Databricks task " + taskRunId));
    assertEquals("1234", job.getTag("databricks_job_id"));
    assertEquals("5678", job.getTag("databricks_job_run_id"));
    assertLink(job, expected.getTraceId(), expected.getSpanId());
  }

  private static void setDatabricksTask(
      SparkSession spark, String jobId, String jobRunId, String taskRunId, String repairPayload) {
    spark.sparkContext().setLocalProperty("spark.databricks.job.id", jobId);
    spark.sparkContext().setLocalProperty("spark.databricks.job.parentRunId", jobRunId);
    spark.sparkContext().setLocalProperty("spark.databricks.job.runId", taskRunId);
    spark.sparkContext().setLocalProperty("unity.scope.data", repairPayload);
  }

  private static String encodedRepairAttempt(int attempt) {
    byte[] key = "jobRunAttemptNum".getBytes(StandardCharsets.UTF_8);
    byte[] value = Integer.toString(attempt).getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.write(key, 0, key.length);
    payload.write(0x74);
    payload.write((value.length >>> 8) & 0xff);
    payload.write(value.length & 0xff);
    payload.write(value, 0, value.length);
    return Base64.getEncoder().encodeToString(payload.toByteArray());
  }

  private static SparkListenerJobStart jobStart(int jobId) {
    // Spark changed this constructor from scala.collection.Seq to immutable.Seq in Scala 2.13.
    // Reflection keeps this shared fixture binary-compatible with both Scala versions.
    try {
      for (Constructor<?> constructor : SparkListenerJobStart.class.getConstructors()) {
        if (constructor.getParameterCount() == 4) {
          return (SparkListenerJobStart)
              constructor.newInstance(
                  jobId,
                  System.currentTimeMillis(),
                  scala.collection.immutable.Nil$.MODULE$,
                  new Properties());
        }
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("could not construct SparkListenerJobStart", e);
    }
    throw new AssertionError("missing SparkListenerJobStart constructor");
  }

  private static final class MinimalSparkListener extends AbstractDatadogSparkListener {
    private MinimalSparkListener(SparkConf conf, String appId, String sparkVersion) {
      super(conf, appId, sparkVersion);
    }

    @Override
    protected String getSparkJobName(SparkListenerJobStart jobStart) {
      return null;
    }

    @Override
    protected ArrayList<Integer> getSparkJobStageIds(SparkListenerJobStart jobStart) {
      return new ArrayList<>();
    }

    @Override
    protected int getStageCount(SparkListenerJobStart jobStart) {
      return 0;
    }

    @Override
    protected java.util.Collection<SparkPlanInfo> getPlanInfoChildren(SparkPlanInfo info) {
      return Collections.emptyList();
    }

    @Override
    protected List<SQLMetricInfo> getPlanInfoMetrics(SparkPlanInfo info) {
      return Collections.emptyList();
    }

    @Override
    protected int[] getStageParentIds(StageInfo info) {
      return new int[0];
    }

    @Override
    protected List<AccumulatorV2> getExternalAccumulators(TaskMetrics metrics) {
      return Collections.emptyList();
    }
  }

  private static final class RecordingSparkListener extends SparkListener {
    private final AtomicInteger applicationStarts = new AtomicInteger();
    private final AtomicInteger applicationEnds = new AtomicInteger();

    @Override
    public void onApplicationStart(SparkListenerApplicationStart event) {
      applicationStarts.incrementAndGet();
    }

    @Override
    public void onApplicationEnd(SparkListenerApplicationEnd event) {
      applicationEnds.incrementAndGet();
    }
  }
}

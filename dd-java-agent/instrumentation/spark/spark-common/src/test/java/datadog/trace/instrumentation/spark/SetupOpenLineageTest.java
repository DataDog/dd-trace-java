package datadog.trace.instrumentation.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.IdGenerationStrategy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.spark.SparkConf;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.SparkListenerInterface;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.util.AccumulatorV2;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class SetupOpenLineageTest {

  private static TestListener newListenerWithOpenLineage() {
    TestListener listener = new TestListener(new SparkConf(), "some_app_id", "some_version");
    // The constructor registers a JVM shutdown hook that finishes the application trace. In a plain
    // unit test there is no registered tracer, so that hook would NPE at JVM exit. Mark the
    // application as already ended so the hook short-circuits.
    markApplicationEnded(listener);
    listener.openLineageSparkListener = mock(SparkListenerInterface.class);
    listener.openLineageSparkConf = new SparkConf();
    return listener;
  }

  private static void markApplicationEnded(AbstractDatadogSparkListener listener) {
    try {
      Field applicationEnded =
          AbstractDatadogSparkListener.class.getDeclaredField("applicationEnded");
      applicationEnded.setAccessible(true);
      applicationEnded.setBoolean(listener, true);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Could not mark application as ended", e);
    }
  }

  private static boolean spanLinksEnabled(AbstractDatadogSparkListener listener) {
    try {
      Field field = AbstractDatadogSparkListener.class.getDeclaredField("spanLinksEnabled");
      field.setAccessible(true);
      return field.getBoolean(listener);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Could not read the listener's span links mode", e);
    }
  }

  private static List<String> runTags(TestListener listener) {
    return Arrays.asList(
        listener.openLineageSparkConf.get("spark.openlineage.run.tags").split(";"));
  }

  @Test
  void addsOlEnvTagWhenEnvIsSet() {
    TestListener listener = newListenerWithOpenLineage();

    Config config = mock(Config.class);
    when(config.getEnv()).thenReturn("my-env");
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      configMock.when(Config::get).thenReturn(config);
      listener.setupOpenLineage(mock(DDTraceId.class));
    }

    assertTrue(runTags(listener).contains("_dd.ol_env:my-env"));
  }

  @Test
  void omitsOlEnvTagWhenEnvIsEmpty() {
    TestListener listener = newListenerWithOpenLineage();

    Config config = mock(Config.class);
    when(config.getEnv()).thenReturn("");
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      configMock.when(Config::get).thenReturn(config);
      listener.setupOpenLineage(mock(DDTraceId.class));
    }

    assertFalse(
        runTags(listener).stream().anyMatch(tag -> tag.startsWith("_dd.ol_env:")),
        "No _dd.ol_env tag should be set when env is empty");
  }

  @TableTest({
    "scenario          | dedicated | experimental | expected",
    "both disabled     | false     | false        | false   ",
    "dedicated only    | true      | false        | true    ",
    "experimental only | false     | true         | true    ",
    "both enabled      | true      | true         | true    "
  })
  void capturesEffectiveSpanLinksModeAtConstruction(
      boolean dedicated, boolean experimental, boolean expected) {
    Config config = mock(Config.class);
    when(config.isDataJobsSparkSpanLinksEnabled()).thenReturn(dedicated);
    when(config.isDataJobsExperimentalFeaturesEnabled()).thenReturn(experimental);
    when(config.getIdGenerationStrategy()).thenReturn(IdGenerationStrategy.fromName("random"));

    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      configMock.when(Config::get).thenReturn(config);
      TestListener listener = new TestListener(new SparkConf(), "some_app_id", "some_version");
      markApplicationEnded(listener);

      assertEquals(expected, spanLinksEnabled(listener));

      // Configuration changes after construction apply only after a driver restart/new listener.
      when(config.isDataJobsSparkSpanLinksEnabled()).thenReturn(!dedicated);
      when(config.isDataJobsExperimentalFeaturesEnabled()).thenReturn(!experimental);
      assertEquals(expected, spanLinksEnabled(listener));
    }
  }

  /**
   * Minimal concrete listener so the version-agnostic {@link
   * AbstractDatadogSparkListener#setupOpenLineage} logic can be exercised without a running Spark
   * context. The scala-version-specific abstract methods are irrelevant here and return empty
   * results.
   */
  private static class TestListener extends AbstractDatadogSparkListener {
    TestListener(SparkConf sparkConf, String appId, String sparkVersion) {
      super(sparkConf, appId, sparkVersion);
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
    protected Collection<SparkPlanInfo> getPlanInfoChildren(SparkPlanInfo info) {
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
}

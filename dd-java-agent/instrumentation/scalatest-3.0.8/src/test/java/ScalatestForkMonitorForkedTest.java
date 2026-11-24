import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.civisibility.events.NoOpTestEventsHandler;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scalatest.tools.Framework;
import sbt.testing.Fingerprint;
import sbt.testing.Logger;
import sbt.testing.Runner;
import sbt.testing.Selector;
import sbt.testing.SubclassFingerprint;
import sbt.testing.SuiteSelector;
import sbt.testing.Task;
import sbt.testing.TaskDef;

@WithConfig(key = CIVISIBILITY_ENABLED, value = "true")
@WithConfig(key = CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED, value = "true")
class ScalatestForkMonitorForkedTest extends AbstractInstrumentationTest {
  static final List<String> EVENTS = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void registerEventsHandler() {
    InstrumentationBridge.registerTestEventsHandlerFactory(new RecordingEventsHandler.Factory());
  }

  @Test
  void reportsSuitesRunInTheSbtJvm() {
    Framework framework = new Framework();
    Fingerprint fingerprint =
        asList(framework.fingerprints()).stream()
            .filter(SubclassFingerprint.class::isInstance)
            .findFirst()
            .get();
    TaskDef taskDef =
        new TaskDef(
            "org.example.TestSucceed", fingerprint, false, new Selector[] {new SuiteSelector()});

    Runner runner = framework.runner(new String[0], new String[0], getClass().getClassLoader());
    Deque<Task> tasks = new ArrayDeque<>(asList(runner.tasks(new TaskDef[] {taskDef})));
    while (!tasks.isEmpty()) {
      tasks.addAll(asList(tasks.poll().execute(event -> {}, new Logger[0])));
    }
    runner.done();

    assertEquals(
        asList(
            "suite start org.example.TestSucceed",
            "test start Example.add adds two numbers",
            "test finish",
            "suite finish"),
        EVENTS);
  }

  static class RecordingEventsHandler
      extends NoOpTestEventsHandler<TestSuiteDescriptor, TestDescriptor> {
    @Override
    public void onTestSuiteStart(
        TestSuiteDescriptor descriptor,
        String testSuiteName,
        String testFramework,
        String testFrameworkVersion,
        Class<?> testClass,
        Collection<String> categories,
        boolean parallelized,
        TestFrameworkInstrumentation instrumentation,
        Long startTime) {
      EVENTS.add("suite start " + testSuiteName);
    }

    @Override
    public void onTestSuiteFinish(TestSuiteDescriptor descriptor, Long endTime) {
      EVENTS.add("suite finish");
    }

    @Override
    public void onTestStart(
        TestSuiteDescriptor suiteDescriptor,
        TestDescriptor descriptor,
        String testName,
        String testFramework,
        String testFrameworkVersion,
        String testParameters,
        Collection<String> categories,
        TestSourceData testSourceData,
        Long startTime,
        TestExecutionTracker testExecutionTracker) {
      EVENTS.add("test start " + testName);
    }

    @Override
    public void onTestFinish(
        TestDescriptor descriptor, Long endTime, TestExecutionTracker executionTracker) {
      EVENTS.add("test finish");
    }

    static class Factory implements TestEventsHandler.Factory {
      @Override
      @SuppressWarnings("unchecked")
      public <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
          String component,
          ContextStore<SuiteKey, DDTestSuite> suiteStore,
          ContextStore<TestKey, DDTest> testStore,
          Collection<LibraryCapability> capabilities) {
        return (TestEventsHandler<SuiteKey, TestKey>) new RecordingEventsHandler();
      }
    }
  }
}

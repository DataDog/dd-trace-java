package datadog.trace.instrumentation.karate;

import com.intuit.karate.KarateException;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureResult;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioIterator;
import com.intuit.karate.core.ScenarioResult;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;

public class KarateTracingHook implements RuntimeHook {

  private static final String FRAMEWORK_NAME = "karate";
  public static final String FRAMEWORK_VERSION = KarateUtils.getKarateVersion();
  public static final String KARATE_STEP_SPAN_NAME = "karate.step";

  private final ContextStore<FeatureRuntime, FeatureSuite> featureSuites;

  public KarateTracingHook(ContextStore<FeatureRuntime, FeatureSuite> featureSuites) {
    this.featureSuites = featureSuites;
  }

  @Override
  public boolean beforeFeature(FeatureRuntime fr) {
    if (skipTracking(fr)) {
      return true;
    }
    FeatureSuite featureSuite = featureSuites.getOrCompute(fr, KarateTracingHook::newFeatureSuite);
    synchronized (featureSuite) {
      startSuite(fr, featureSuite, false);
      if (!isFeatureContainingScenarios(fr)) {
        // Karate will not trigger the afterFeature hook if suite has no scenarios
        featureSuite.featureFinished = true;
        finishSuite(fr, featureSuite);
      }
    }
    return true;
  }

  private static FeatureSuite newFeatureSuite(FeatureRuntime fr) {
    return new FeatureSuite();
  }

  private static void startSuite(FeatureRuntime fr, FeatureSuite featureSuite, boolean detached) {
    if (featureSuite.started) {
      return;
    }
    featureSuite.started = true;
    featureSuite.detached = detached;
    Feature feature = KarateUtils.getFeature(fr);
    // Karate can start and finish a feature's suite on different threads
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        fr,
        feature.getNameForReport(),
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        KarateUtils.getCategories(feature.getTags()),
        true,
        TestFrameworkInstrumentation.KARATE,
        null);
  }

  private static void finishSuite(FeatureRuntime fr, FeatureSuite featureSuite) {
    if (!featureSuite.started) {
      return;
    }
    featureSuite.started = false;
    if (featureSuite.detached) {
      // the feature result covers scenarios that ran outside this suite
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(fr, null);
      return;
    }
    FeatureResult result = fr.result;
    if (result.isFailed()) {
      KarateException throwable = result.getErrorMessagesCombined();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(fr, throwable);
    } else if (result.isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(fr, null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(fr, null);
  }

  private boolean isFeatureContainingScenarios(FeatureRuntime fr) {
    // cannot use existing iterator (FeatureRuntime#scenarios) because it may have been traversed
    // already
    // (likely, when scheduling parallel execution of scenarios)
    return new ScenarioIterator(fr).filterSelected().iterator().hasNext();
  }

  @Override
  public void afterFeature(FeatureRuntime fr) {
    if (skipTracking(fr)) {
      return;
    }
    FeatureSuite featureSuite = featureSuites.get(fr);
    if (featureSuite == null) {
      return;
    }
    synchronized (featureSuite) {
      // karate-junit5 can call this hook while scenarios of the feature are still running
      featureSuite.featureFinished = true;
      if (featureSuite.runningScenarios.isEmpty()) {
        finishSuite(fr, featureSuite);
      }
    }
  }

  private void scenarioStarted(ScenarioRuntime sr) {
    FeatureRuntime fr = sr.featureRuntime;
    FeatureSuite featureSuite = featureSuites.getOrCompute(fr, KarateTracingHook::newFeatureSuite);
    synchronized (featureSuite) {
      if (!featureSuite.started) {
        if (!KarateUtils.isBeforeHookExecuted(fr)) {
          // Karate does not call feature hooks for some scenarios, for example with built-in
          // retries
          featureSuite.featureFinished = true;
        }
        startSuite(fr, featureSuite, true);
      }
      featureSuite.runningScenarios.add(sr);
    }
  }

  private void scenarioFinished(ScenarioRuntime sr) {
    FeatureRuntime fr = sr.featureRuntime;
    FeatureSuite featureSuite = featureSuites.get(fr);
    if (featureSuite == null) {
      return;
    }
    synchronized (featureSuite) {
      if (featureSuite.runningScenarios.remove(sr)
          && featureSuite.featureFinished
          && featureSuite.runningScenarios.isEmpty()) {
        finishSuite(fr, featureSuite);
      }
    }
  }

  @Override
  public boolean beforeScenario(ScenarioRuntime sr) {
    if (skipTracking(sr)) {
      return true;
    }
    Scenario scenario = sr.scenario;
    FeatureRuntime fr = sr.featureRuntime;
    scenarioStarted(sr);

    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    Collection<String> categories = scenario.getTagsEffective().getTagKeys();

    if (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled()) {
      TestIdentifier skippableTest = KarateUtils.toTestIdentifier(scenario);
      SkipReason skipReason =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skipReason(
              skippableTest, TestSourceData.UNKNOWN, categories);

      if (skipReason != null
          && !(skipReason == SkipReason.ITR
              && categories.contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG))) {
        TestExecutionTracker executionTracker =
            (TestExecutionTracker)
                sr.magicVariables.get(KarateUtils.EXECUTION_TRACKER_MAGICVARIABLE);
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
            fr,
            testDescriptor,
            scenarioName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            parameters,
            categories,
            TestSourceData.UNKNOWN,
            skipReason.getDescription(),
            executionTracker);
        // Karate does not call afterScenario for a scenario skipped by a hook
        scenarioFinished(sr);
        return false;
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        fr,
        testDescriptor,
        scenarioName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        parameters,
        categories,
        TestSourceData.UNKNOWN,
        null,
        (TestExecutionTracker) sr.magicVariables.get(KarateUtils.EXECUTION_TRACKER_MAGICVARIABLE));
    return true;
  }

  @Override
  public void afterScenario(ScenarioRuntime sr) {
    if (skipTracking(sr)) {
      return;
    }
    ScenarioResult result = sr.result;
    Throwable failedReason = getFailedReason(result);
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);
    if (result.isFailed() || failedReason != null) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, failedReason);
    } else if (result.getStepResults().isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, null);
    }

    TestExecutionTracker executionTracker =
        (TestExecutionTracker) sr.magicVariables.get(KarateUtils.EXECUTION_TRACKER_MAGICVARIABLE);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testDescriptor, null, executionTracker);

    scenarioFinished(sr);
  }

  private Throwable getFailedReason(ScenarioResult result) {
    Throwable error = result.getError();
    if (error != null) {
      return error;
    }
    // check if any step result has a "suppressed" error
    // (manually set by flaky test retries logic - to avoid failing the build)
    List<StepResult> stepResults = result.getStepResults();
    for (StepResult stepResult : stepResults) {
      Throwable failedReason = stepResult.getFailedReason();
      if (failedReason != null) {
        return failedReason;
      }
    }
    return null;
  }

  @Override
  public boolean beforeStep(Step step, ScenarioRuntime sr) {
    if (skipTracking(step)) {
      return true;
    }
    AgentSpan span = AgentTracer.startSpan("karate", KARATE_STEP_SPAN_NAME);
    AgentTracer.activateSpanWithoutScope(span);
    String stepName = step.getPrefix() + " " + step.getText();
    span.setResourceName(stepName);
    span.setTag(Tags.COMPONENT, "karate");
    span.spanContext().setIntegrationName("karate");
    span.setTag("step.name", stepName);
    span.setTag("step.startLine", step.getLine());
    span.setTag("step.endLine", step.getEndLine());
    span.setTag("step.docString", step.getDocString());
    return true;
  }

  @Override
  public void afterStep(StepResult result, ScenarioRuntime sr) {
    if (skipTracking(result.getStep())) {
      return;
    }

    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    AgentTracer.closeActive();
    span.finish();
  }

  private static boolean skipTracking(FeatureRuntime fr) {
    // do not track nested feature calls
    return !fr.caller.isNone();
  }

  public static boolean skipTracking(ScenarioRuntime sr) {
    // do not track nested scenario calls and setup scenarios
    return !sr.caller.isNone() || sr.tags.getTagKeys().contains("setup");
  }

  private static boolean skipTracking(Step step) {
    // do not track steps that are not children of a tracked scenario or another tracked step
    AgentSpan activeSpan = AgentTracer.activeSpan();
    return activeSpan == null
        || (!KARATE_STEP_SPAN_NAME.contentEquals(activeSpan.getSpanName())
            && !Tags.SPAN_KIND_TEST.contentEquals(activeSpan.getSpanType()));
  }
}

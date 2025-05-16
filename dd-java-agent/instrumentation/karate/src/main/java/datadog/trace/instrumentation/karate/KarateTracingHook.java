package datadog.trace.instrumentation.karate;

import com.intuit.karate.KarateException;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.Suite;
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
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
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

  private final ContextStore<FeatureRuntime, Boolean> manualFeatureHooks;

  public KarateTracingHook(ContextStore<FeatureRuntime, Boolean> manualFeatureHooks) {
    this.manualFeatureHooks = manualFeatureHooks;
  }

  @Override
  public boolean beforeFeature(FeatureRuntime fr) {
    if (skipTracking(fr)) {
      return true;
    }
    Suite suite = fr.suite;
    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(fr);
    Feature feature = KarateUtils.getFeature(fr);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        feature.getNameForReport(),
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        KarateUtils.getCategories(feature.getTags()),
        suite.parallel,
        TestFrameworkInstrumentation.KARATE,
        null);

    if (!isFeatureContainingScenarios(fr)) {
      // Karate will not trigger the afterFeature hook if suite has no scenarios
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, null);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor, null);
    }

    return true;
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
    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(fr);
    FeatureResult result = fr.result;
    if (result.isFailed()) {
      KarateException throwable = result.getErrorMessagesCombined();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(suiteDescriptor, throwable);
    } else if (result.isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor, null);
  }

  @Override
  public boolean beforeScenario(ScenarioRuntime sr) {
    if (skipTracking(sr)) {
      return true;
    }
    Scenario scenario = sr.scenario;

    // There are cases when Karate does not call "beforeFeature" hooks,
    // for example when using built-in retries
    boolean beforeFeatureHookExecuted = KarateUtils.isBeforeHookExecuted(sr.featureRuntime);
    if (!beforeFeatureHookExecuted) {
      beforeFeature(sr.featureRuntime);
      manualFeatureHooks.put(sr.featureRuntime, true);
    }

    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(sr.featureRuntime);
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    Collection<String> categories = scenario.getTagsEffective().getTagKeys();

    if (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled()) {
      TestIdentifier skippableTest = KarateUtils.toTestIdentifier(scenario);
      SkipReason skipReason = TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skipReason(skippableTest);

      if (skipReason != null
          && !(skipReason == SkipReason.ITR
              && categories.contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG))) {
        TestExecutionHistory executionHistory =
            (TestExecutionHistory)
                sr.magicVariables.get(KarateUtils.EXECUTION_HISTORY_MAGICVARIABLE);
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
            suiteDescriptor,
            testDescriptor,
            scenarioName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            parameters,
            categories,
            TestSourceData.UNKNOWN,
            skipReason.getDescription(),
            executionHistory);
        return false;
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        suiteDescriptor,
        testDescriptor,
        scenarioName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        parameters,
        categories,
        TestSourceData.UNKNOWN,
        null,
        (TestExecutionHistory) sr.magicVariables.get(KarateUtils.EXECUTION_HISTORY_MAGICVARIABLE));
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

    TestExecutionHistory executionHistory =
        (TestExecutionHistory) sr.magicVariables.get(KarateUtils.EXECUTION_HISTORY_MAGICVARIABLE);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testDescriptor, null, executionHistory);

    Boolean runHooksManually = manualFeatureHooks.remove(sr.featureRuntime);
    if (runHooksManually != null && runHooksManually) {
      afterFeature(sr.featureRuntime);
      KarateUtils.resetBeforeHook(sr.featureRuntime);
    }
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
    span.context().setIntegrationName("karate");
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

  private static boolean skipTracking(ScenarioRuntime sr) {
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

package datadog.trace.instrumentation.karate2;

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
import io.karatelabs.core.FeatureResult;
import io.karatelabs.core.FeatureRunEvent;
import io.karatelabs.core.FeatureRuntime;
import io.karatelabs.core.RunEvent;
import io.karatelabs.core.RunEventType;
import io.karatelabs.core.RunListener;
import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.ScenarioRunEvent;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.StepResult;
import io.karatelabs.core.StepRunEvent;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Step;
import java.util.Collection;
import java.util.List;

public class KarateTracingListener implements RunListener {

  private static final String FRAMEWORK_NAME = "karate";
  public static final String FRAMEWORK_VERSION = KarateUtils.getKarateVersion();
  public static final String KARATE_STEP_SPAN_NAME = "karate.step";

  private final ContextStore<Scenario, ExecutionContext> scenarioContext;
  private final ContextStore<FeatureRuntime, FeatureSuite> featureSuites;

  public KarateTracingListener(
      ContextStore<Scenario, ExecutionContext> scenarioContext,
      ContextStore<FeatureRuntime, FeatureSuite> featureSuites) {
    this.scenarioContext = scenarioContext;
    this.featureSuites = featureSuites;
  }

  @Override
  public boolean onEvent(RunEvent event) {
    RunEventType type = event.getType();
    if (type == RunEventType.FEATURE_ENTER) {
      return beforeFeature((FeatureRunEvent) event);
    } else if (type == RunEventType.FEATURE_EXIT) {
      afterFeature((FeatureRunEvent) event);
    } else if (type == RunEventType.SCENARIO_ENTER) {
      return beforeScenario((ScenarioRunEvent) event);
    } else if (type == RunEventType.SCENARIO_EXIT) {
      afterScenario((ScenarioRunEvent) event);
    } else if (type == RunEventType.STEP_ENTER) {
      beforeStep((StepRunEvent) event);
    } else if (type == RunEventType.STEP_EXIT) {
      afterStep((StepRunEvent) event);
    }
    return true;
  }

  private void afterScenario(ScenarioRunEvent event) {
    ScenarioRuntime sr = event.source();
    Scenario scenario = sr.getScenario();
    // A context means call advice will finish the test after scenario post-processing.
    if (scenarioContext.get(scenario) == null) {
      afterScenario(sr, event.result(), null, featureSuites);
    }
  }

  private boolean beforeFeature(FeatureRunEvent event) {
    FeatureRuntime fr = event.source();
    if (skipTracking(fr)) {
      return true;
    }
    FeatureSuite featureSuite =
        featureSuites.getOrCompute(fr, KarateTracingListener::newFeatureSuite);
    synchronized (featureSuite) {
      startSuite(fr, featureSuite, false);
    }
    return true;
  }

  private void afterFeature(FeatureRunEvent event) {
    FeatureRuntime fr = event.source();
    if (skipTracking(fr)) {
      return;
    }
    FeatureSuite featureSuite = featureSuites.get(fr);
    if (featureSuite == null) {
      return;
    }
    synchronized (featureSuite) {
      featureSuite.featureFinished = true;
      if (featureSuite.runningScenarios.isEmpty()) {
        finishSuite(fr, featureSuite);
      }
    }
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
    Feature feature = fr.getFeature();
    // Karate can start and finish a feature's suite on different threads
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        fr,
        KarateUtils.getFeatureNameForReport(feature),
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
    FeatureResult result = fr.getResult();
    if (result != null && result.isFailed()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(fr, suiteThrowable(result));
    } else if (result != null && result.isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(fr, null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(fr, null);
  }

  private void scenarioStarted(ScenarioRuntime sr) {
    FeatureRuntime fr = sr.getFeatureRuntime();
    FeatureSuite featureSuite =
        featureSuites.getOrCompute(fr, KarateTracingListener::newFeatureSuite);
    synchronized (featureSuite) {
      if (!featureSuite.started) {
        // the scenario runs outside its feature's lifecycle, for example when retried by a wrapper
        featureSuite.featureFinished = true;
        startSuite(fr, featureSuite, true);
      }
      featureSuite.runningScenarios.add(sr);
    }
  }

  private static void scenarioFinished(
      ScenarioRuntime sr, ContextStore<FeatureRuntime, FeatureSuite> featureSuites) {
    FeatureRuntime fr = sr.getFeatureRuntime();
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

  private boolean beforeScenario(ScenarioRunEvent event) {
    ScenarioRuntime sr = event.source();
    if (skipTracking(sr)) {
      return true;
    }
    Scenario scenario = sr.getScenario();
    FeatureRuntime fr = sr.getFeatureRuntime();
    scenarioStarted(sr);
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    Collection<String> categories = KarateUtils.getCategories(scenario.getTagsEffective());

    ExecutionContext context = scenarioContext.get(scenario);
    TestExecutionTracker executionTracker = context != null ? context.getExecutionPolicy() : null;

    if (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled()) {
      TestIdentifier skippableTest = KarateUtils.toTestIdentifier(scenario);
      SkipReason skipReason =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skipReason(
              skippableTest, TestSourceData.UNKNOWN, categories);

      if (skipReason != null
          && !(skipReason == SkipReason.ITR
              && categories.contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG))) {
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
        // onTestIgnore already finished the test
        scenarioFinished(sr, featureSuites);
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
        executionTracker);
    if (context != null) {
      context.setTestStarted(true);
    }
    return true;
  }

  public static void afterScenario(
      ScenarioRuntime sr,
      ScenarioResult result,
      ExecutionContext context,
      ContextStore<FeatureRuntime, FeatureSuite> featureSuites) {
    if (skipTracking(sr)) {
      return;
    }
    Scenario scenario = sr.getScenario();
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);

    Throwable suppressedError = context != null ? context.getAndClearSuppressedError() : null;
    // @fail retains the expected step error even when the final result passes, so only inspect
    // failed results for an error
    boolean resultFailed = result != null && result.isFailed();
    Throwable failedReason =
        resultFailed ? getFailedReason(result, suppressedError) : suppressedError;

    if (resultFailed || failedReason != null) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, failedReason);
    } else if (result == null || result.getStepResults().isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, null);
    }

    TestExecutionTracker executionTracker = context != null ? context.getExecutionPolicy() : null;
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testDescriptor, null, executionTracker);

    scenarioFinished(sr, featureSuites);
  }

  private void beforeStep(StepRunEvent event) {
    Step step = event.step();
    if (skipTracking(step)) {
      return;
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
  }

  private void afterStep(StepRunEvent event) {
    if (skipTracking(event.step())) {
      return;
    }

    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    AgentTracer.closeActive();
    span.finish();
  }

  private static Throwable getFailedReason(ScenarioResult result, Throwable suppressedError) {
    if (result != null) {
      Throwable error = result.getError();
      if (error != null) {
        return error;
      }
      for (StepResult stepResult : result.getStepResults()) {
        if (stepResult.getError() != null) {
          return stepResult.getError();
        }
      }
    }
    // a failure that was suppressed by the retry logic (the failing step was replaced with a
    // skipped one) is carried out-of-band on the ExecutionContext
    return suppressedError;
  }

  private static Throwable suiteThrowable(FeatureResult result) {
    List<ScenarioResult> scenarioResults = result.getScenarioResults();
    if (scenarioResults != null) {
      for (ScenarioResult scenarioResult : scenarioResults) {
        Throwable error = scenarioResult.getError();
        if (error != null) {
          return error;
        }
      }
    }
    return new RuntimeException(result.getFailureMessage());
  }

  private static boolean skipTracking(FeatureRuntime fr) {
    // do not track nested (called) feature runs
    return fr.getCaller() != null;
  }

  public static boolean skipTracking(ScenarioRuntime sr) {
    // do not track nested (called) scenario runs and setup scenarios
    FeatureRuntime fr = sr.getFeatureRuntime();
    return fr == null || fr.getCaller() != null || sr.getScenario().isSetup();
  }

  private static boolean skipTracking(Step step) {
    // do not track steps that are not children of a tracked scenario or another tracked step
    AgentSpan activeSpan = AgentTracer.activeSpan();
    return activeSpan == null
        || (!KARATE_STEP_SPAN_NAME.contentEquals(activeSpan.getSpanName())
            && !Tags.SPAN_KIND_TEST.contentEquals(activeSpan.getSpanType()));
  }
}

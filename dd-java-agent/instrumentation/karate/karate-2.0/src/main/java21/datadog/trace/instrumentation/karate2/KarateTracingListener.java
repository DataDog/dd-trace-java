package datadog.trace.instrumentation.karate2;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
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

  private boolean beforeFeature(FeatureRunEvent event) {
    FeatureRuntime fr = event.source();
    if (skipTracking(fr)) {
      return true;
    }
    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(fr);
    Feature feature = fr.getFeature();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        KarateUtils.getFeatureNameForReport(feature),
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        KarateUtils.getCategories(feature.getTags()),
        isParallel(fr),
        TestFrameworkInstrumentation.KARATE,
        null);
    return true;
  }

  private void afterFeature(FeatureRunEvent event) {
    FeatureRuntime fr = event.source();
    if (skipTracking(fr)) {
      return;
    }
    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(fr);
    FeatureResult result = event.result();
    if (result != null && result.isFailed()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          suiteDescriptor, suiteThrowable(result));
    } else if (result != null && result.isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor, null);
  }

  private boolean beforeScenario(ScenarioRunEvent event) {
    ScenarioRuntime sr = event.source();
    if (skipTracking(sr)) {
      return true;
    }
    Scenario scenario = sr.getScenario();
    TestSuiteDescriptor suiteDescriptor = KarateUtils.toSuiteDescriptor(sr.getFeatureRuntime());
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    Collection<String> categories = KarateUtils.getCategories(scenario.getTagsEffective());

    if (Config.get().isCiVisibilityTestSkippingEnabled()
        || Config.get().isCiVisibilityTestManagementEnabled()) {
      TestIdentifier skippableTest = KarateUtils.toTestIdentifier(scenario);
      SkipReason skipReason = TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skipReason(skippableTest);

      if (skipReason != null
          && !(skipReason == SkipReason.ITR
              && categories.contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG))) {
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
            null);
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
        null);
    return true;
  }

  private void afterScenario(ScenarioRunEvent event) {
    ScenarioRuntime sr = event.source();
    if (skipTracking(sr)) {
      return;
    }
    ScenarioResult result = event.result();
    TestDescriptor testDescriptor = KarateUtils.toTestDescriptor(sr);

    Throwable failedReason = getFailedReason(result);
    if ((result != null && result.isFailed()) || failedReason != null) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, failedReason);
    } else if (result == null || result.getStepResults().isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, null);
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(testDescriptor, null, null);
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

  private static Throwable getFailedReason(ScenarioResult result) {
    if (result == null) {
      return null;
    }
    Throwable error = result.getError();
    if (error != null) {
      return error;
    }
    for (StepResult stepResult : result.getStepResults()) {
      if (stepResult.getError() != null) {
        return stepResult.getError();
      }
    }
    return null;
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

  private static boolean isParallel(FeatureRuntime fr) {
    return fr.getSuite() != null && fr.getSuite().parallel;
  }

  private static boolean skipTracking(FeatureRuntime fr) {
    // do not track nested (called) feature runs
    return fr.getCaller() != null;
  }

  private static boolean skipTracking(ScenarioRuntime sr) {
    // do not track nested (called) scenario runs and setup scenarios
    return sr.getFeatureRuntime().getCaller() != null || sr.getScenario().isSetup();
  }

  private static boolean skipTracking(Step step) {
    // do not track steps that are not children of a tracked scenario or another tracked step
    AgentSpan activeSpan = AgentTracer.activeSpan();
    return activeSpan == null
        || (!KARATE_STEP_SPAN_NAME.contentEquals(activeSpan.getSpanName())
            && !Tags.SPAN_KIND_TEST.contentEquals(activeSpan.getSpanType()));
  }
}

package datadog.trace.instrumentation.karate;

import com.intuit.karate.FileUtils;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.Suite;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureResult;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;

public class KarateTracingHook implements RuntimeHook {

  private static final String FRAMEWORK_NAME = "karate";
  private static final String FRAMEWORK_VERSION = FileUtils.KARATE_VERSION;

  @Override
  public boolean beforeFeature(FeatureRuntime fr) {
    if (skipTracking(fr)) {
      return true;
    }
    Feature feature = KarateUtils.getFeature(fr);
    Suite suite = fr.suite;
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        feature.getNameForReport(),
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        KarateUtils.getCategories(feature.getTags()),
        suite.parallel);
    return true;
  }

  @Override
  public void afterFeature(FeatureRuntime fr) {
    if (skipTracking(fr)) {
      return;
    }
    String featureName = KarateUtils.getFeature(fr).getNameForReport();
    FeatureResult result = fr.result;
    if (result.isFailed()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          featureName, null, result.getErrorMessagesCombined());
    } else if (result.isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(featureName, null, null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(featureName, null);
  }

  @Override
  public boolean beforeScenario(ScenarioRuntime sr) {
    if (skipTracking(sr)) {
      return true;
    }
    Scenario scenario = sr.scenario;
    Feature feature = scenario.getFeature();

    String featureName = feature.getNameForReport();
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    Collection<String> categories = scenario.getTagsEffective().getTagKeys();

    if (Config.get().isCiVisibilityItrEnabled()
        && !categories.contains(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)) {
      SkippableTest skippableTest = new SkippableTest(featureName, scenarioName, parameters, null);
      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(skippableTest)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
            featureName,
            scenarioName,
            sr,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            parameters,
            categories,
            null,
            null,
            null,
            InstrumentationBridge.ITR_SKIP_REASON);
        return false;
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        featureName,
        scenarioName,
        sr,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        parameters,
        categories,
        null,
        null,
        null);
    return true;
  }

  @Override
  public void afterScenario(ScenarioRuntime sr) {
    if (skipTracking(sr)) {
      return;
    }
    Scenario scenario = sr.scenario;
    Feature feature = scenario.getFeature();

    String featureName = feature.getNameForReport();
    String scenarioName = KarateUtils.getScenarioName(scenario);

    ScenarioResult result = sr.result;
    if (result.isFailed()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
          featureName,
          null,
          scenarioName,
          sr,
          KarateUtils.getParameters(scenario),
          result.getError());
    } else if (result.getStepResults().isEmpty()) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
          featureName, null, scenarioName, sr, KarateUtils.getParameters(scenario), null);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        featureName, null, scenarioName, sr, KarateUtils.getParameters(scenario));
  }

  @Override
  public boolean beforeStep(Step step, ScenarioRuntime sr) {
    if (skipTracking(step)) {
      return true;
    }
    AgentSpan span = AgentTracer.startSpan("karate", "karate.step");
    AgentScope scope = AgentTracer.activateSpan(span);
    String stepName = step.getPrefix() + " " + step.getText();
    span.setResourceName(stepName);
    span.setTag(Tags.COMPONENT, "karate");
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

    AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

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
    // do not track steps that do not belong to a tracked scenario
    AgentSpan activeSpan = AgentTracer.activeSpan();
    return activeSpan == null
        || activeSpan.getSpanType() == null
        || !Tags.SPAN_KIND_TEST.contentEquals(activeSpan.getSpanType());
  }
}

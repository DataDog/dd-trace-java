package datadog.trace.instrumentation.karate;

import static datadog.json.JsonMapper.toJson;

import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.Result;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Tag;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class KarateUtils {

  public static final String RETRY_MAGIC_VARIABLE = "__datadog_retry";

  private KarateUtils() {}

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(FeatureRuntime.class.getClassLoader());
  private static final MethodHandle FEATURE_RUNTIME_FEATURE_GETTER =
      METHOD_HANDLES.privateFieldGetter(FeatureRuntime.class, "feature");
  private static final MethodHandle FEATURE_RUNTIME_FEATURE_CALL_GETTER =
      METHOD_HANDLES.privateFieldGetter(FeatureRuntime.class, "featureCall");
  private static final MethodHandle FEATURE_RUNTIME_BEFORE_HOOK_DONE_GETTER =
      METHOD_HANDLES.privateFieldGetter(FeatureRuntime.class, "beforeHookDone");
  private static final MethodHandle FEATURE_RUNTIME_BEFORE_HOOK_DONE_SETTER =
      METHOD_HANDLES.privateFieldSetter(FeatureRuntime.class, "beforeHookDone");
  private static final MethodHandle FEATURE_CALL_FEATURE_GETTER =
      METHOD_HANDLES.privateFieldGetter("com.intuit.karate.core.FeatureCall", "feature");
  private static final MethodHandle ABORTED_RESULT_DURATION_NANOS =
      METHOD_HANDLES.method(Result.class, "aborted", long.class);
  // static method to create aborted result has a different signature starting with Karate 1.4.1
  private static final MethodHandle ABORTED_RESULT_STARTTIME_DURATION_NANOS =
      METHOD_HANDLES.method(Result.class, "aborted", long.class, long.class);

  public static Feature getFeature(FeatureRuntime featureRuntime) {
    if (FEATURE_RUNTIME_FEATURE_CALL_GETTER != null) {
      Object featureCall =
          METHOD_HANDLES.invoke(FEATURE_RUNTIME_FEATURE_CALL_GETTER, featureRuntime);
      if (featureCall != null && FEATURE_CALL_FEATURE_GETTER != null) {
        return METHOD_HANDLES.invoke(FEATURE_CALL_FEATURE_GETTER, featureCall);
      }
    } else if (FEATURE_RUNTIME_FEATURE_GETTER != null) {
      // Karate versions prior to 1.3.0
      return METHOD_HANDLES.invoke(FEATURE_RUNTIME_FEATURE_GETTER, featureRuntime);
    }
    return null;
  }

  public static String getScenarioName(Scenario scenario) {
    String scenarioName = scenario.getName();
    if (Strings.isNotBlank(scenarioName)) {
      return scenarioName;
    } else {
      return scenario.getRefId();
    }
  }

  public static List<String> getCategories(List<Tag> tags) {
    if (tags == null) {
      return Collections.emptyList();
    }

    List<String> categories = new ArrayList<>(tags.size());
    for (Tag tag : tags) {
      categories.add(tag.getName());
    }
    return categories;
  }

  public static String getParameters(Scenario scenario) {
    return scenario.getExampleData() != null ? toJson(scenario.getExampleData()) : null;
  }

  public static TestIdentifier toTestIdentifier(Scenario scenario) {
    Feature feature = scenario.getFeature();
    String featureName = feature.getNameForReport();
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    return new TestIdentifier(featureName, scenarioName, parameters);
  }

  public static TestDescriptor toTestDescriptor(ScenarioRuntime scenarioRuntime) {
    Scenario scenario = scenarioRuntime.scenario;
    Feature feature = scenario.getFeature();
    String featureName = feature.getNameForReport();
    String scenarioName = KarateUtils.getScenarioName(scenario);
    String parameters = KarateUtils.getParameters(scenario);
    return new TestDescriptor(featureName, null, scenarioName, parameters, scenarioRuntime);
  }

  public static TestSuiteDescriptor toSuiteDescriptor(FeatureRuntime featureRuntime) {
    String featureName = KarateUtils.getFeature(featureRuntime).getNameForReport();
    return new TestSuiteDescriptor(featureName, null);
  }

  public static Result abortedResult() {
    if (ABORTED_RESULT_STARTTIME_DURATION_NANOS != null) {
      long startTime = System.currentTimeMillis();
      long durationNanos = 1;
      return METHOD_HANDLES.invoke(
          ABORTED_RESULT_STARTTIME_DURATION_NANOS, startTime, durationNanos);
    } else {
      long durationNanos = 1;
      return METHOD_HANDLES.invoke(ABORTED_RESULT_DURATION_NANOS, durationNanos);
    }
  }

  public static boolean isBeforeHookExecuted(FeatureRuntime featureRuntime) {
    Boolean beforeHookDone =
        METHOD_HANDLES.invoke(FEATURE_RUNTIME_BEFORE_HOOK_DONE_GETTER, featureRuntime);
    return beforeHookDone != null ? beforeHookDone : true;
  }

  public static void resetBeforeHook(FeatureRuntime featureRuntime) {
    METHOD_HANDLES.invoke(FEATURE_RUNTIME_BEFORE_HOOK_DONE_SETTER, featureRuntime, false);
  }
}

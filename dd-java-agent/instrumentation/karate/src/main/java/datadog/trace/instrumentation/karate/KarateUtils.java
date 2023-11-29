package datadog.trace.instrumentation.karate;

import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.Tag;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.Strings;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class KarateUtils {

  private KarateUtils() {}

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(FeatureRuntime.class.getClassLoader());
  private static final MethodHandle FEATURE_RUNTIME_FEATURE_GETTER =
      METHOD_HANDLES.privateFieldGetter("com.intuit.karate.core.FeatureRuntime", "feature");
  private static final MethodHandle FEATURE_RUNTIME_FEATURE_CALL_GETTER =
      METHOD_HANDLES.privateFieldGetter("com.intuit.karate.core.FeatureRuntime", "featureCall");
  private static final MethodHandle FEATURE_CALL_FEATURE_GETTER =
      METHOD_HANDLES.privateFieldGetter("com.intuit.karate.core.FeatureCall", "feature");

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
    return scenario.getExampleData() != null ? Strings.toJson(scenario.getExampleData()) : null;
  }
}

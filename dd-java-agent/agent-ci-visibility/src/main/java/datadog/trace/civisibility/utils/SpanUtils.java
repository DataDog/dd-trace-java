package datadog.trace.civisibility.utils;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.ipc.TestFramework;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.function.Consumer;

public class SpanUtils {
  public static final Consumer<AgentSpan> DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS = span -> {};

  public static Consumer<AgentSpan> propagateCiVisibilityTagsTo(AgentSpan parentSpan) {
    return childSpan -> propagateCiVisibilityTags(parentSpan, childSpan);
  }

  public static void propagateCiVisibilityTags(AgentSpan parentSpan, AgentSpan childSpan) {
    mergeTestFrameworks(parentSpan, getFrameworks(childSpan));
    propagateStatus(parentSpan, childSpan);
  }

  public static void mergeTestFrameworks(AgentSpan span, Collection<TestFramework> testFrameworks) {
    Collection<TestFramework> spanFrameworks = getFrameworks(span);
    Collection<TestFramework> merged = merge(spanFrameworks, testFrameworks);
    setFrameworks(span, merged);
  }

  private static Collection<TestFramework> getFrameworks(AgentSpan span) {
    Object nameTag = span.getTag(Tags.TEST_FRAMEWORK);
    Object versionTag = span.getTag(Tags.TEST_FRAMEWORK_VERSION);
    if (nameTag == null && versionTag == null) {
      return Collections.emptyList();
    }

    Collection<TestFramework> frameworks = new ArrayList<>();
    if (nameTag instanceof String) {
      frameworks.add(new TestFramework((String) nameTag, (String) versionTag));

    } else if (nameTag instanceof Collection) {
      Iterator<String> names = ((Collection<String>) nameTag).iterator();
      Iterator<String> versions = ((Collection<String>) versionTag).iterator();
      while (names.hasNext()) {
        frameworks.add(new TestFramework(names.next(), versions.next()));
      }

    } else {
      throw new IllegalArgumentException(
          "Unexpected tag type(s): "
              + Tags.TEST_FRAMEWORK
              + " ("
              + nameTag
              + ") "
              + Tags.TEST_FRAMEWORK_VERSION
              + " ("
              + versionTag
              + ")");
    }
    return frameworks;
  }

  private static Collection<TestFramework> merge(
      Collection<TestFramework> parentFrameworks, Collection<TestFramework> childFrameworks) {
    if (parentFrameworks.isEmpty()) {
      return childFrameworks;
    }
    if (childFrameworks.isEmpty()) {
      return parentFrameworks;
    }
    Collection<TestFramework> merged = new TreeSet<>();
    merged.addAll(parentFrameworks);
    merged.addAll(childFrameworks);
    return merged;
  }

  private static void setFrameworks(AgentSpan span, Collection<TestFramework> frameworks) {
    if (frameworks.isEmpty()) {
      return;
    }
    if (frameworks.size() == 1) {
      TestFramework framework = frameworks.iterator().next();
      span.setTag(Tags.TEST_FRAMEWORK, framework.getName());
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, framework.getVersion());
      return;
    }
    Collection<String> names = new ArrayList<>(frameworks.size());
    Collection<String> versions = new ArrayList<>(frameworks.size());
    for (TestFramework framework : frameworks) {
      names.add(framework.getName());
      versions.add(framework.getVersion());
    }
    span.setTag(Tags.TEST_FRAMEWORK, names);
    span.setTag(Tags.TEST_FRAMEWORK_VERSION, versions);
  }

  private static void propagateStatus(AgentSpan parentSpan, AgentSpan childSpan) {
    String childStatus = (String) childSpan.getTag(Tags.TEST_STATUS);
    if (childStatus == null) {
      return;
    }

    String parentStatus = (String) parentSpan.getTag(Tags.TEST_STATUS);
    switch (childStatus) {
      case CIConstants.TEST_PASS:
        if (parentStatus == null || CIConstants.TEST_SKIP.equals(parentStatus)) {
          parentSpan.setTag(Tags.TEST_STATUS, CIConstants.TEST_PASS);
        }
        break;
      case CIConstants.TEST_FAIL:
        parentSpan.setTag(Tags.TEST_STATUS, CIConstants.TEST_FAIL);
        break;
      case CIConstants.TEST_SKIP:
        if (parentStatus == null) {
          parentSpan.setTag(Tags.TEST_STATUS, CIConstants.TEST_SKIP);
        }
        break;
      default:
        break;
    }
  }

  public static void propagateTags(AgentSpan parentSpan, AgentSpan childSpan, String... tagNames) {
    for (String tagName : tagNames) {
      parentSpan.setTag(tagName, childSpan.getTag(tagName));
    }
  }
}

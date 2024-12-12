package datadog.trace.civisibility.utils;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.TestStatus;
import datadog.trace.civisibility.ipc.TestFramework;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
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
    TestStatus childStatus = (TestStatus) childSpan.getTag(Tags.TEST_STATUS);
    if (childStatus == null) {
      return;
    }

    TestStatus parentStatus = (TestStatus) parentSpan.getTag(Tags.TEST_STATUS);
    switch (childStatus) {
      case pass:
        if (parentStatus == null || TestStatus.skip.equals(parentStatus)) {
          parentSpan.setTag(Tags.TEST_STATUS, TestStatus.pass);
        }
        break;
      case fail:
        parentSpan.setTag(Tags.TEST_STATUS, TestStatus.fail);
        break;
      case skip:
        if (parentStatus == null) {
          parentSpan.setTag(Tags.TEST_STATUS, TestStatus.skip);
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

  public static <T> void propagateTag(AgentSpan parentSpan, AgentSpan childSpan, String tagName) {
    propagateTag(parentSpan, childSpan, tagName, (p, c) -> c);
  }

  public static <T> void propagateTag(
      AgentSpan parentSpan, AgentSpan childSpan, String tagName, BinaryOperator<T> mergeStrategy) {
    T childTag = (T) childSpan.getTag(tagName);
    if (childTag != null) {
      T parentTag = (T) parentSpan.getTag(tagName);
      if (parentTag == null) {
        parentSpan.setTag(tagName, childTag);
      } else {
        parentSpan.setTag(tagName, mergeStrategy.apply(parentTag, childTag));
      }
    }
  }
}

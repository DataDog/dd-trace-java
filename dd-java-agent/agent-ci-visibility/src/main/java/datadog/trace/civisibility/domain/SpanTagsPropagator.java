package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.ipc.TestFramework;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

public class SpanTagsPropagator {
  public static final Consumer<AgentSpan> NOOP_PROPAGATOR = span -> {};

  private final AgentSpan parentSpan;
  private final Object tagPropagationLock = new Object();

  public SpanTagsPropagator(AgentSpan parentSpan) {
    this.parentSpan = parentSpan;
  }

  public void propagateCiVisibilityTags(AgentSpan childSpan) {
    mergeTestFrameworks(getFrameworks(childSpan));
    propagateStatus(childSpan);
  }

  public void propagateStatus(AgentSpan childSpan) {
    synchronized (tagPropagationLock) {
      unsafePropagateStatus(childSpan);
    }
  }

  public void mergeTestFrameworks(Collection<TestFramework> testFrameworks) {
    synchronized (tagPropagationLock) {
      unsafeMergeTestFrameworks(testFrameworks);
    }
  }

  public void propagateTags(AgentSpan childSpan, TagMergeSpec<?>... specs) {
    synchronized (tagPropagationLock) {
      for (TagMergeSpec<?> spec : specs) {
        unsafePropagateTag(childSpan, spec);
      }
    }
  }

  private void unsafeMergeTestFrameworks(Collection<TestFramework> childFrameworks) {
    Collection<TestFramework> parentFrameworks = getFrameworks(parentSpan);
    Collection<TestFramework> merged = merge(parentFrameworks, childFrameworks);
    setFrameworks(merged);
  }

  static Collection<TestFramework> getFrameworks(AgentSpan span) {
    Object nameTag = span.getTag(Tags.TEST_FRAMEWORK);
    Object versionTag = span.getTag(Tags.TEST_FRAMEWORK_VERSION);
    if (nameTag == null) {
      return Collections.emptyList();
    }

    Collection<TestFramework> frameworks = new ArrayList<>();
    if (nameTag instanceof String) {
      frameworks.add(new TestFramework((String) nameTag, (String) versionTag));

    } else if (nameTag instanceof Collection) {
      Iterator<String> names = ((Collection<String>) nameTag).iterator();
      Iterator<String> versions =
          versionTag != null ? ((Collection<String>) versionTag).iterator() : null;
      while (names.hasNext()) {
        String version = (versions != null && versions.hasNext()) ? versions.next() : null;
        frameworks.add(new TestFramework(names.next(), version));
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

  private void setFrameworks(Collection<TestFramework> frameworks) {
    if (frameworks.isEmpty()) {
      return;
    }
    if (frameworks.size() == 1) {
      TestFramework framework = frameworks.iterator().next();
      Map<String, String> tags = new HashMap<>();
      tags.put(Tags.TEST_FRAMEWORK, framework.getName());
      if (framework.getVersion() != null) {
        tags.put(Tags.TEST_FRAMEWORK_VERSION, framework.getVersion());
      }
      parentSpan.setAllTags(tags);
      return;
    }
    Collection<String> names = new ArrayList<>(frameworks.size());
    Collection<String> versions = new ArrayList<>(frameworks.size());
    for (TestFramework framework : frameworks) {
      names.add(framework.getName());
      versions.add(framework.getVersion());
    }
    Map<String, Collection<String>> tags = new HashMap<>();
    tags.put(Tags.TEST_FRAMEWORK, names);
    tags.put(Tags.TEST_FRAMEWORK_VERSION, versions);
    parentSpan.setAllTags(tags);
  }

  private void unsafePropagateStatus(AgentSpan childSpan) {
    TestStatus childStatus = (TestStatus) childSpan.getTag(Tags.TEST_STATUS);
    if (childStatus == null) {
      return;
    }

    Boolean childFailureSuppressed = (Boolean) childSpan.getTag(Tags.TEST_FAILURE_SUPPRESSED);
    TestStatus parentStatus = (TestStatus) parentSpan.getTag(Tags.TEST_STATUS);
    if (childStatus == TestStatus.pass
        || (childFailureSuppressed != null && childFailureSuppressed)) {
      if (parentStatus == null || TestStatus.skip.equals(parentStatus)) {
        parentSpan.setTag(Tags.TEST_STATUS, TestStatus.pass);
      }
    } else if (childStatus == TestStatus.fail) {
      parentSpan.setTag(Tags.TEST_STATUS, TestStatus.fail);
    } else if (childStatus == TestStatus.skip) {
      if (parentStatus == null) {
        parentSpan.setTag(Tags.TEST_STATUS, TestStatus.skip);
      }
    }
  }

  public static class TagMergeSpec<T> {
    private final String tagKey;
    private final BinaryOperator<T> mergeFunction;

    TagMergeSpec(String tagKey, BinaryOperator<T> mergeFunction) {
      this.tagKey = tagKey;
      this.mergeFunction = mergeFunction;
    }

    public static <T> TagMergeSpec<T> of(String key, BinaryOperator<T> mergeFunction) {
      return new TagMergeSpec<>(key, mergeFunction);
    }

    public static TagMergeSpec<Object> of(String tagKey) {
      return new TagMergeSpec<>(tagKey, (parent, child) -> child);
    }
  }

  private <T> void unsafePropagateTag(AgentSpan childSpan, TagMergeSpec<T> spec) {
    T childTag = (T) childSpan.getTag(spec.tagKey);
    if (childTag != null) {
      T parentTag = (T) parentSpan.getTag(spec.tagKey);
      if (parentTag == null) {
        parentSpan.setTag(spec.tagKey, childTag);
      } else {
        parentSpan.setTag(spec.tagKey, spec.mergeFunction.apply(parentTag, childTag));
      }
    }
  }
}

package datadog.trace.civisibility.utils;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

public class SpanUtils {
  public static final Consumer<AgentSpan> DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS = span -> {};

  public static Consumer<AgentSpan> propagateCiVisibilityTagsTo(AgentSpan parentSpan) {
    return childSpan -> propagateCiVisibilityTags(parentSpan, childSpan);
  }

  public static void propagateCiVisibilityTags(AgentSpan parentSpan, AgentSpan childSpan) {
    mergeTag(parentSpan, childSpan, Tags.TEST_FRAMEWORK);
    mergeTag(parentSpan, childSpan, Tags.TEST_FRAMEWORK_VERSION);
    propagateStatus(parentSpan, childSpan);
  }

  public static void mergeTag(AgentSpan parentSpan, AgentSpan childSpan, String tagName) {
    mergeTag(parentSpan, tagName, childSpan.getTag(tagName));
  }

  public static void mergeTag(AgentSpan span, String tagName, Object tagValue) {
    if (tagValue == null) {
      return;
    }

    Object existingValue = span.getTag(tagName);
    if (existingValue == null) {
      span.setTag(tagName, tagValue);
      return;
    }

    if (existingValue.equals(tagValue)) {
      return;
    }

    Collection<Object> updatedValue = new ArrayList<>();
    if (existingValue instanceof Collection) {
      updatedValue.addAll((Collection<Object>) existingValue);
    } else {
      updatedValue.add(existingValue);
    }

    if (tagValue instanceof Collection) {
      for (Object value : (Collection<Object>) tagValue) {
        if (!updatedValue.contains(value)) {
          updatedValue.add(value);
        }
      }
    } else {
      if (!updatedValue.contains(tagValue)) {
        updatedValue.add(tagValue);
      }
    }
    span.setTag(tagName, updatedValue);
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
}

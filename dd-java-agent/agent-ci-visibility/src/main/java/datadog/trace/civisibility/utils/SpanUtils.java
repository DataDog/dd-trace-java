package datadog.trace.civisibility.utils;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.function.Consumer;

public class SpanUtils {

  public static final Consumer<AgentSpan> DO_NOT_PROPAGATE_CI_VISIBILITY_TAGS = span -> {};

  public static Consumer<AgentSpan> propagateCiVisibilityTagsTo(AgentSpan parentSpan) {
    return childSpan -> propagateCiVisibilityTags(parentSpan, childSpan);
  }

  public static void propagateCiVisibilityTags(AgentSpan parentSpan, AgentSpan childSpan) {
    parentSpan.setTag(Tags.TEST_FRAMEWORK, childSpan.getTag(Tags.TEST_FRAMEWORK));
    parentSpan.setTag(Tags.TEST_FRAMEWORK_VERSION, childSpan.getTag(Tags.TEST_FRAMEWORK_VERSION));

    propagateStatus(parentSpan, childSpan);
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

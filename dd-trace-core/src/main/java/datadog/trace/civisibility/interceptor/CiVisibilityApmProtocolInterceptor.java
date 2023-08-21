package datadog.trace.civisibility.interceptor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An interceptor that removes spans and tags that are not supported in the APM protocol (some spans
 * and tags related to certain CI Visibility features, e.g. Test Suite Level Visibility, can only be
 * written with CI Test Cycle protocol)
 */
public class CiVisibilityApmProtocolInterceptor extends AbstractTraceInterceptor {

  public static final CiVisibilityApmProtocolInterceptor INSTANCE =
      new CiVisibilityApmProtocolInterceptor(Priority.CI_VISIBILITY_APM);

  protected CiVisibilityApmProtocolInterceptor(Priority priority) {
    super(priority);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {

    List<? extends MutableSpan> filteredTrace =
        trace.stream().filter(this::isSupportedByApmProtocol).collect(Collectors.toList());
    for (MutableSpan span : filteredTrace) {
      span.setTag(Tags.TEST_SESSION_ID, (Number) null);
      span.setTag(Tags.TEST_MODULE_ID, (Number) null);
      span.setTag(Tags.TEST_SUITE_ID, (Number) null);
    }
    return filteredTrace;
  }

  private boolean isSupportedByApmProtocol(MutableSpan span) {
    String spanType = span.getSpanType();
    return !DDSpanTypes.TEST_SESSION_END.equals(spanType)
        && !DDSpanTypes.TEST_MODULE_END.equals(spanType)
        && !DDSpanTypes.TEST_SUITE_END.equals(spanType);
  }
}

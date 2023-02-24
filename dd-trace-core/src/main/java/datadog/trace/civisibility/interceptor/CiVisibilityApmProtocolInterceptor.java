package datadog.trace.civisibility.interceptor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An interceptor that removes spans and tags that are not supported in the APM protocol (some spans
 * and tags related to certain CI Visibility features, e.g. Test Suite Level Visibility, can only be
 * written with CI Test Cycle protocol)
 */
public class CiVisibilityApmProtocolInterceptor implements TraceInterceptor {

  public static final CiVisibilityApmProtocolInterceptor INSTANCE =
      new CiVisibilityApmProtocolInterceptor();

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
    return !DDSpanTypes.TEST_MODULE_END.equals(spanType)
        && !DDSpanTypes.TEST_SUITE_END.equals(spanType);
  }

  @Override
  public int priority() {
    return 1;
  }
}

package datadog.trace.civisibility.interceptor;

import static datadog.trace.api.civisibility.CIConstants.CIAPP_TEST_ORIGIN;

import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDTraceCoreInfo;
import java.util.Collection;
import java.util.Collections;

public class CiVisibilityTraceInterceptor extends AbstractTraceInterceptor {

  public static final CiVisibilityTraceInterceptor INSTANCE =
      new CiVisibilityTraceInterceptor(Priority.CI_VISIBILITY_TRACE);

  protected CiVisibilityTraceInterceptor(Priority priority) {
    super(priority);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (trace.isEmpty()) {
      return trace;
    }

    final DDSpan firstSpan = (DDSpan) trace.iterator().next();
    final DDSpan localRootSpan = firstSpan.getLocalRootSpan();

    final DDSpan spanToCheck = null == localRootSpan ? firstSpan : localRootSpan;

    // If root span does not originate from CI visibility, we drop the full trace.
    CharSequence origin = spanToCheck.getOrigin();
    if (origin == null || !CIAPP_TEST_ORIGIN.contentEquals(origin)) {
      return Collections.emptyList();
    }

    // If the trace belongs to a "test", we need to set the `library_version` tag for all spans.
    for (MutableSpan span : trace) {
      span.setTag(DDTags.LIBRARY_VERSION_TAG_KEY, DDTraceCoreInfo.VERSION);
    }

    return trace;
  }
}

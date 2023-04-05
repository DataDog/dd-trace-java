package datadog.trace.civisibility.interceptor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDTraceCoreInfo;
import java.util.Collection;
import java.util.Collections;

public class CiVisibilityTraceInterceptor extends AbstractTraceInterceptor {

  public static final CiVisibilityTraceInterceptor INSTANCE =
      new CiVisibilityTraceInterceptor(Priority.CI_VISIBILITY_TRACE);

  static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

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

    // If root span is not a CI visibility span, we drop the full trace.
    CharSequence type = spanToCheck.getType(); // Don't null pointer if there is no type
    if (type == null
        || (!DDSpanTypes.TEST.contentEquals(type)
            && !DDSpanTypes.TEST_SUITE_END.contentEquals(type)
            && !DDSpanTypes.TEST_MODULE_END.contentEquals(type)
            && !DDSpanTypes.TEST_SESSION_END.contentEquals(type))) {
      return Collections.emptyList();
    }

    // If the trace belongs to a "test", we need to set the origin to `ciapp-test` and the
    // `library_version` tag for all spans.
    firstSpan.context().setOrigin(CIAPP_TEST_ORIGIN);
    firstSpan.setTag(DDTags.LIBRARY_VERSION_TAG_KEY, DDTraceCoreInfo.VERSION);
    for (MutableSpan span : trace) {
      span.setTag(DDTags.LIBRARY_VERSION_TAG_KEY, DDTraceCoreInfo.VERSION);
    }

    return trace;
  }
}

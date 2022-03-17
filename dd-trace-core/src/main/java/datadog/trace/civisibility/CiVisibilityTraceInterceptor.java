package datadog.trace.civisibility;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpan;
import java.util.Collection;
import java.util.Collections;

public class CiVisibilityTraceInterceptor implements TraceInterceptor {

  public static final CiVisibilityTraceInterceptor INSTANCE = new CiVisibilityTraceInterceptor();

  static final String TEST_TYPE = "test";
  static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (trace.isEmpty()) {
      return trace;
    }

    final DDSpan firstSpan = (DDSpan) trace.iterator().next();
    final DDSpan localRootSpan = firstSpan.getLocalRootSpan();

    final DDSpan spanToCheck = null == localRootSpan ? firstSpan : localRootSpan;

    // If root span does not have type == "test", we drop the full trace.
    CharSequence type = spanToCheck.getType(); // Don't null pointer if there is no type
    if (type == null || !TEST_TYPE.contentEquals(type)) {
      return Collections.emptyList();
    }

    // If the trace belongs to a "test", we need to set the origin of all the spans of the trace to
    // `ciapp-test`.
    for (MutableSpan span : trace) {
      ((DDSpan) span).context().setOrigin(CIAPP_TEST_ORIGIN);
    }

    return trace;
  }

  @Override
  public int priority() {
    return 0;
  }
}

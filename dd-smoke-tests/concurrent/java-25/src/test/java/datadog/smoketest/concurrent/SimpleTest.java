package datadog.smoketest.concurrent;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.function.Predicate;

abstract class SimpleTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected Predicate<DecodedTrace> checkTrace() {
    // 'parent' with a single 'child'
    return trace ->
        trace.getSpans().size() == 2
            && findRootSpan(trace, "parent")
                .filter(parent -> hasChildSpan(trace, "child", parent.getSpanId()))
                .isPresent();
  }
}

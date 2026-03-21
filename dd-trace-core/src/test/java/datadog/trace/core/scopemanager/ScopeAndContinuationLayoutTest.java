package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;

class ScopeAndContinuationLayoutTest {

  @Test
  void continuableScopeLayout() {
    assumeFalse(System.getProperty("java.vendor").toUpperCase().contains("IBM"));
    assertTrue(layoutAcceptable(ContinuableScope.class, 32));
  }

  @Test
  void singleContinuationLayout() {
    assumeFalse(System.getProperty("java.vendor").toUpperCase().contains("IBM"));
    assertTrue(layoutAcceptable(ScopeContinuation.class, 32));
  }

  private boolean layoutAcceptable(Class<?> klass, int acceptableSize) {
    ClassLayout layout = ClassLayout.parseClass(klass);
    System.err.println(layout.toPrintable());
    return layout.instanceSize() <= acceptableSize;
  }
}

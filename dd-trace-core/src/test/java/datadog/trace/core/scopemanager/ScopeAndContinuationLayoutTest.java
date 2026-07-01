package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;

class ScopeAndContinuationLayoutTest extends DDJavaSpecification {

  @BeforeAll
  static void assumeNotIbmJvm() {
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  @Test
  void continuableScopeLayout() {
    assertTrue(layoutAcceptable(ContinuableScope.class, 32));
  }

  @Test
  void singleContinuationLayout() {
    assertTrue(layoutAcceptable(ScopeContinuation.class, 32));
  }

  private boolean layoutAcceptable(Class<?> klass, int acceptableSize) {
    ClassLayout layout = ClassLayout.parseClass(klass);
    System.err.println(layout.toPrintable());
    return layout.instanceSize() <= acceptableSize;
  }
}

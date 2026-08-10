package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import io.opentracing.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OTSpanTest extends DDJavaSpecification {

  static DDTracer tracer;

  @BeforeAll
  static void setUpClass() {
    tracer = DDTracer.builder().build();
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testResourceNameAssignmentThroughMutableSpanCasting() {
    OTSpan testSpan = (OTSpan) tracer.buildSpan("parent").withResourceName("test-resource").start();
    OTScopeManager.OTScope testScope = (OTScopeManager.OTScope) tracer.activateSpan(testSpan);

    Span active = tracer.activeSpan();
    Span child = tracer.buildSpan("child").asChildOf(active).start();
    Scope scope = tracer.activateSpan(child);

    MutableSpan localRootSpan = ((MutableSpan) child).getLocalRootSpan();
    localRootSpan.setResourceName("correct-resource");

    assertEquals("correct-resource", testSpan.getResourceName());

    testSpan
        .getDelegate()
        .setResourceName("should-be-ignored", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE);

    assertEquals("correct-resource", testSpan.getResourceName());

    scope.close();
    child.finish();
    testScope.close();
    testSpan.finish();
  }
}

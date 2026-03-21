package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class BlackholeSpanTest extends DDCoreSpecification {

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  void shouldMuteTracing(String moreBits) throws Exception {
    injectSysConfig("trace.128.bit.traceid.logging.enabled", moreBits);
    ListWriter writer = new ListWriter();
    Properties props = new Properties();
    CoreTracer tracer = tracerBuilder().withProperties(props).writer(writer).build();
    try {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan child = null;
      datadog.trace.bootstrap.instrumentation.api.AgentSpan bh = null;
      datadog.trace.bootstrap.instrumentation.api.AgentSpan ignored = null;
      datadog.trace.bootstrap.instrumentation.api.AgentSpan root = tracer.startSpan("test", "root");
      datadog.trace.context.TraceScope scope1 = tracer.activateSpan(root);
      try {
        bh = tracer.blackholeSpan();
        datadog.trace.context.TraceScope scope2 = tracer.activateSpan(bh);
        try {
          ignored = tracer.startSpan("test", "ignored");
          ignored.finish();
        } finally {
          bh.finish();
          scope2.close();
        }
        child = tracer.startSpan("test", "child");
        child.finish();
      } finally {
        root.finish();
        scope1.close();
      }
      writer.waitForTraces(1);
      assertEquals(2, writer.firstTrace().size());
      assertTrue(writer.firstTrace().contains(root));
      assertTrue(writer.firstTrace().contains(child));
      assertFalse(writer.firstTrace().contains(bh));
      assertFalse(writer.firstTrace().contains(ignored));
    } finally {
      writer.close();
      tracer.close();
    }
  }
}

package datadog.trace.core;

import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class BlackholeSpanTest extends DDCoreJavaSpecification {

  @ValueSource(strings = {"true", "false"})
  @ParameterizedTest
  void shouldMuteTracing(String use128bitTraceId) throws Exception {
    injectSysConfig("trace.128.bit.traceid.logging.enabled", use128bitTraceId);
    ListWriter writer = new ListWriter();
    Properties props = new Properties();
    CoreTracer tracer = tracerBuilder().withProperties(props).writer(writer).build();
    try {
      AgentSpan root = tracer.startSpan("test", "root");
      AgentScope scope1 = tracer.activateSpan(root);
      AgentSpan bh;
      AgentSpan ignored;
      AgentSpan child;
      try {
        bh = tracer.blackholeSpan();
        AgentScope scope2 = tracer.activateSpan(bh);
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
      assertTrue(writer.firstTrace().containsAll(Arrays.asList(root, child)));
      assertFalse(writer.firstTrace().contains(bh));
      assertFalse(writer.firstTrace().contains(ignored));
    } finally {
      writer.close();
      tracer.close();
    }
  }
}

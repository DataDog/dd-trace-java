import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.opentracing.DDTracer;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import io.opentracing.Span;
import org.junit.jupiter.api.Test;

abstract class CorrelationIdInjectorTest extends DDJavaSpecification {

  String logPattern =
      "TRACE_ID=%X{"
          + CorrelationIdentifier.getTraceIdKey()
          + "} SPAN_ID=%X{"
          + CorrelationIdentifier.getSpanIdKey()
          + "} %m";

  @Test
  void testCorrelationIdInjection() throws Exception {
    DDTracer tracer = buildTracer();
    LogJournal journal = buildJournal();
    TestLogger logger = buildLogger();

    logger.log("Event without context");

    assertEquals("TRACE_ID= SPAN_ID= Event without context", journal.nextLog());

    Span rootSpan = tracer.buildSpan("operation1").start();
    Scope rootScope = tracer.activateSpan(rootSpan);
    logger.log("Event with root span context");

    assertEquals(
        "TRACE_ID="
            + CorrelationIdentifier.getTraceId()
            + " SPAN_ID="
            + CorrelationIdentifier.getSpanId()
            + " Event with root span context",
        journal.nextLog());

    Span childSpan = tracer.buildSpan("operation1").asChildOf(rootSpan).start();
    Scope childScope = tracer.activateSpan(childSpan);
    logger.log("Event with child span context");

    assertEquals(
        "TRACE_ID="
            + CorrelationIdentifier.getTraceId()
            + " SPAN_ID="
            + CorrelationIdentifier.getSpanId()
            + " Event with child span context",
        journal.nextLog());

    childScope.close();
    childSpan.finish();
    logger.log("Event with root span context");

    assertEquals(
        "TRACE_ID="
            + CorrelationIdentifier.getTraceId()
            + " SPAN_ID="
            + CorrelationIdentifier.getSpanId()
            + " Event with root span context",
        journal.nextLog());

    rootScope.close();
    rootSpan.finish();
    logger.log("Event without context");

    assertEquals("TRACE_ID= SPAN_ID= Event without context", journal.nextLog());

    tracer.close();
  }

  DDTracer buildTracer() {
    DDTracer tracer = new DDTracer.DDTracerBuilder().build();
    GlobalTracer.registerIfAbsent(tracer);
    return tracer;
  }

  abstract LogJournal buildJournal();

  abstract TestLogger buildLogger();

  interface LogJournal {
    String nextLog();
  }

  interface TestLogger {
    void log(String message);
  }
}

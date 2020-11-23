package datadog.smoketest.slf4jmdc;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Trace;
import datadog.trace.api.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class Slf4jMDCApp {

  private static final Logger log = LoggerFactory.getLogger(Slf4jMDCApp.class);

  public static void main(String... args) {
    try {
      for (String arg : args) {
        trace(Integer.parseInt(arg));
      }
    } catch (Throwable t) {
      log.error("Fail", t);
    }
  }

  private static void trace(final int max) {
    trace(0, max);
  }

  @Trace
  private static void trace(int current, final int max) {
    if (current < max) {
      current++;
      Tracer tracer = GlobalTracer.get();
      log.debug(
          "Iteration|{}|{}|GT|{}|GS|{}|MT|{}|MS|{}",
          current,
          max,
          tracer.getTraceId(),
          tracer.getSpanId(),
          MDC.get("dd.trace_id"),
          MDC.get("dd.span_id"));
      trace(current, max);
      log.debug(
          "Iteration|{}|{}|GT|{}|GS|{}|MT|{}|MS|{}",
          -current,
          max,
          tracer.getTraceId(),
          tracer.getSpanId(),
          MDC.get("dd.trace_id"),
          MDC.get("dd.span_id"));
    }
  }
}

package datadog.trace.common.writer;

import static datadog.trace.common.serialization.JsonFormatWriter.TRACE_ADAPTER;

import datadog.opentracing.DDSpan;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingWriter implements Writer {

  @Override
  public void write(final List<DDSpan> trace) {
    if (log.isInfoEnabled()) {
      try {
        log.info("write(trace): {}", toString(trace));
      } catch (final Exception e) {
        log.error("error writing(trace): {}", trace);
      }
    }
  }

  private String toString(final List<DDSpan> trace) {
    return TRACE_ADAPTER.toJson(trace);
  }

  @Override
  public void incrementTraceCount() {
    log.info("incrementTraceCount()");
  }

  @Override
  public void close() {
    log.info("close()");
  }

  @Override
  public void start() {
    log.info("start()");
  }

  @Override
  public String toString() {
    return "LoggingWriter { }";
  }
}

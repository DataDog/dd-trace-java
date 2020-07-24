package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingWriter extends JsonStringWriter {

  @Override
  protected void writeJson(final String json) {
    log.info("write(trace): {}", json);
  }

  @Override
  protected void writeException(final List<DDSpan> trace, final Exception e) {
    log.error("error writing(trace): {}", trace, e);
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

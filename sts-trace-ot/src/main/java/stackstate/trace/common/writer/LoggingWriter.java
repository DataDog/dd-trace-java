package stackstate.trace.common.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import stackstate.opentracing.STSSpan;
import stackstate.trace.common.Service;

@Slf4j
@AutoService(Writer.class)
public class LoggingWriter implements Writer {
  private final ObjectMapper serializer = new ObjectMapper();

  @Override
  public void write(final List<STSSpan> trace) {
    try {
      log.info("write(trace): {}", serializer.writeValueAsString(trace));
    } catch (Exception e) {
      log.error("error writing(trace): {}", trace);
    }
  }

  @Override
  public void writeServices(final Map<String, Service> services) {
    log.info("additional service information: {}", services.values());
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

package stackstate.trace.common.writer;

import stackstate.opentracing.STSSpan;
import stackstate.trace.common.STSTraceConfig;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/** A writer is responsible to send collected spans to some place */
public interface Writer {
  static final String STS_AGENT_WRITER_TYPE = STSAgentWriter.class.getSimpleName();
  static final String LOGGING_WRITER_TYPE = LoggingWriter.class.getSimpleName();

  /**
   * Write a trace represented by the entire list of all the finished spans
   *
   * @param trace the list of spans to write
   */
  void write(List<STSSpan> trace);

  /** Start the writer */
  void start();

  /**
   * Indicates to the writer that no future writing will come and it should terminates all
   * connections and tasks
   */
  void close();

  @Slf4j
  final class Builder {
    public static Writer forConfig(final Properties config) {
      final Writer writer;

      if (config != null) {
        final String configuredType = config.getProperty(STSTraceConfig.WRITER_TYPE);
        if (STS_AGENT_WRITER_TYPE.equals(configuredType)) {
          writer =
              new STSAgentWriter(
                  new STSApi(
                      config.getProperty(STSTraceConfig.AGENT_HOST),
                      Integer.parseInt(config.getProperty(STSTraceConfig.AGENT_PORT))));
        } else if (LOGGING_WRITER_TYPE.equals(configuredType)) {
          writer = new LoggingWriter();
        } else {
          log.warn(
              "Writer type not configured correctly: Type {} not recognized. Defaulting to STSAgentWriter.",
              configuredType);
          writer =
              new STSAgentWriter(
                  new STSApi(
                      config.getProperty(STSTraceConfig.AGENT_HOST),
                      Integer.parseInt(config.getProperty(STSTraceConfig.AGENT_PORT))));
        }
      } else {
        log.warn(
            "Writer type not configured correctly: No config provided! Defaulting to STSAgentWriter.");
        writer = new STSAgentWriter();
      }

      return writer;
    }

    private Builder() {}
  }
}

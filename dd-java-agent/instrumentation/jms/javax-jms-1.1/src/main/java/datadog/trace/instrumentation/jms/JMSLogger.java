package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMSLogger {
  private static final Logger log = LoggerFactory.getLogger(JMSLogger.class);

  public static void logIterationSpan(AgentSpan span) {
    log.debug("Expecting the following `ITERATION` span to be finished {}", span);
  }
}

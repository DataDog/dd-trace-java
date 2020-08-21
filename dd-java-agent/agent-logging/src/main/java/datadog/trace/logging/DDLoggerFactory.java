package datadog.trace.logging;

import datadog.trace.logging.simplelogger.SLCompatFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class DDLoggerFactory implements ILoggerFactory {

  public DDLoggerFactory() {}

  private static LoggerHelperFactory helperFactory = null;

  @Override
  public Logger getLogger(String name) {
    LoggerHelperFactory c = helperFactory;
    if (c == null) {
      c = helperFactory = new SLCompatFactory();
    }
    return new DDLogger(c, name);
  }
}

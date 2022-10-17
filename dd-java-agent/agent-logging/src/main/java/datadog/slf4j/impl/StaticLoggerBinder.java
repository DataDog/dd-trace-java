/**
 * Please note that the package name here needs to match where org.slf4j.impl will be in the
 * shadowed jar.
 */
package datadog.slf4j.impl;

import datadog.trace.logging.ddlogger.DDLoggerFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

public class StaticLoggerBinder implements LoggerFactoryBinder {

  // declare this field to satisfy GraalVM's static analysis
  public static final String REQUESTED_API_VERSION = "1.7.30";

  private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

  public static final StaticLoggerBinder getSingleton() {
    return SINGLETON;
  }

  private static final String loggerFactoryClassStr = DDLoggerFactory.class.getName();

  private final ILoggerFactory loggerFactory;

  private StaticLoggerBinder() {
    loggerFactory = new DDLoggerFactory();
  }

  public ILoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  public String getLoggerFactoryClassStr() {
    return loggerFactoryClassStr;
  }
}

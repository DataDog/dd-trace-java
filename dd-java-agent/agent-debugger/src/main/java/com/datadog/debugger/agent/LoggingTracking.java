package com.datadog.debugger.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

public class LoggingTracking implements ClassFileTransformer {
  private boolean log4j2;
  private ClassLoader lo4j2ClassLoader; // should be a weak ref to avoid CL leak
  private final Map<String, Object> log4J2ConstantLevels = new HashMap<>();
  private boolean logback;
  private ClassLoader logbackClassLoader; // should be a weak ref to avoid CL leak
  private final Map<String, Object> logbackConstantLevels = new HashMap<>();
  private boolean jul;
  private final Map<String, Object> julConstantLevels = new HashMap<>();

  private final Instrumentation instrumentation;

  public LoggingTracking(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
  }

  public Instrumentation getInstrumentation() {
    return instrumentation;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {
    if ("org/apache/logging/log4j/Logger".equals(className)) {
      System.out.println("Found Log4J2");
      log4j2 = true;
      lo4j2ClassLoader = loader;
      initLog4J2ConstantLevels();
      return null;
    }
    if ("ch/qos/logback/classic/Logger".equals(className)) {
      System.out.println("Found Logback");
      logback = true;
      logbackClassLoader = loader;
      initLogBackConstantLevels();
      return null;
    }
    if ("java/util/logging/Logger".equals(className)) {
      System.out.println("Found JUL");
      jul = true;
      // System classloader?
      return null;
    }
    return null;
  }

  public boolean isLog4j2() {
    return log4j2;
  }

  public boolean isLogback() {
    return logback;
  }

  public boolean isJul() {
    return jul;
  }

  public void switchAll(String level) {
    if (log4j2) {
      switchLog4J2(level);
    }
    if (logback) {
      switchLogBack(level);
    }
    if (jul) {
      switchJul(level);
    }
  }

  public void switchLog4J2(String level) {
    try {
      Class<?> logManagerClass =
          Class.forName("org.apache.logging.log4j.LogManager", true, lo4j2ClassLoader);
      Method getContextMethod = logManagerClass.getMethod("getContext", Boolean.TYPE);
      Object ctxt = getContextMethod.invoke(null, false);
      if (!ctxt.getClass().getName().equals("org.apache.logging.log4j.spi.LoggerContext")) {
        System.out.println("not true Log4J2: " + ctxt.getClass().getName());
        return;
      }
      Method getConfigurationMethod = ctxt.getClass().getMethod("getConfiguration");
      Object config = getConfigurationMethod.invoke(ctxt);
      Method getLoggerConfigMethod = config.getClass().getMethod("getLoggerConfig", String.class);
      Class<?> LogManagerClass =
          Class.forName("org.apache.logging.log4j.LogManager", true, lo4j2ClassLoader);
      Field rootLoggerNameField = LogManagerClass.getDeclaredField("ROOT_LOGGER_NAME");
      Object rootLoggerName = rootLoggerNameField.get(null);
      Object loggerConfig = getLoggerConfigMethod.invoke(config, rootLoggerName);
      Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level", true, lo4j2ClassLoader);
      Method setLevelMethod = loggerConfig.getClass().getMethod("setLevel", levelClass);
      Object logLevel = log4J2ConstantLevels.get(level);
      setLevelMethod.invoke(loggerConfig, logLevel);
      Method updateLoggersMethod = ctxt.getClass().getMethod("updateLoggers");
      updateLoggersMethod.invoke(ctxt);
      System.out.println("Log4J2 Changed log level to " + logLevel);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public void switchLogBack(String level) {
    try {
      // to avoid shadowing
      final String LOGGER_FACTORY =
          new StringBuilder("org.").append("slf4j.").append("LoggerFactory").toString();
      final String LOGGER = new StringBuilder("org.").append("slf4j.").append("Logger").toString();
      final String LEVEL =
          new StringBuilder("ch.")
              .append("qos.")
              .append("logback.")
              .append("classic.")
              .append("Level")
              .toString();

      Class<?> loggerFactoryClass = Class.forName(LOGGER_FACTORY, true, logbackClassLoader);
      Method getILoggerFactoryMethod = loggerFactoryClass.getMethod("getILoggerFactory");
      Object context = getILoggerFactoryMethod.invoke(null);
      Method getLoggerMethod = context.getClass().getMethod("getLogger", String.class);
      Class<?> loggerClass = Class.forName(LOGGER, true, logbackClassLoader);
      Field rootLoggerNameField = loggerClass.getDeclaredField("ROOT_LOGGER_NAME");
      Object rootLoggerName = rootLoggerNameField.get(null);
      Object logger = getLoggerMethod.invoke(context, rootLoggerName);
      Class<?> levelClass = Class.forName(LEVEL, true, logbackClassLoader);
      Method setLevelMethod = logger.getClass().getMethod("setLevel", levelClass);
      Object logLevel = logbackConstantLevels.get(level);
      setLevelMethod.invoke(logger, logLevel);
      System.out.println("LogBack Changed log level to " + logLevel);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    /*
    ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.DEBUG;
    ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    logger.setLevel(level);
     */
  }

  public void switchJul(String level) {
    /*
    java.util.logging.Level level = java.util.logging.Level.FINE;
    // appender level
    Logger root = Logger.getGlobal();
    for (Handler handler : root.getParent().getHandlers()) {
        handler.setLevel(level);
    }
    // logger level
    //LOGGER.setLevel(level);
    System.err.println("JUL Changed log level to " + level);

     */
  }

  private void initLog4J2ConstantLevels() {
    try {
      Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level", true, lo4j2ClassLoader);
      Field offField = levelClass.getDeclaredField("OFF");
      Field fatalField = levelClass.getDeclaredField("FATAL");
      Field errorField = levelClass.getDeclaredField("ERROR");
      Field warnField = levelClass.getDeclaredField("WARN");
      Field infoField = levelClass.getDeclaredField("INFO");
      Field debugField = levelClass.getDeclaredField("DEBUG");
      Field traceField = levelClass.getDeclaredField("TRACE");
      log4J2ConstantLevels.put("OFF", offField.get(null));
      log4J2ConstantLevels.put("FATAL", fatalField.get(null));
      log4J2ConstantLevels.put("ERROR", errorField.get(null));
      log4J2ConstantLevels.put("WARN", warnField.get(null));
      log4J2ConstantLevels.put("INFO", infoField.get(null));
      log4J2ConstantLevels.put("DEBUG", debugField.get(null));
      log4J2ConstantLevels.put("TRACE", traceField.get(null));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private void initLogBackConstantLevels() {
    try {
      Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level", true, logbackClassLoader);
      Field offField = levelClass.getDeclaredField("OFF");
      Field errorField = levelClass.getDeclaredField("ERROR");
      Field warnField = levelClass.getDeclaredField("WARN");
      Field infoField = levelClass.getDeclaredField("INFO");
      Field debugField = levelClass.getDeclaredField("DEBUG");
      Field traceField = levelClass.getDeclaredField("TRACE");
      Field allField = levelClass.getDeclaredField("ALL");
      logbackConstantLevels.put("OFF", offField.get(null));
      logbackConstantLevels.put("ERROR", errorField.get(null));
      logbackConstantLevels.put("WARN", warnField.get(null));
      logbackConstantLevels.put("INFO", infoField.get(null));
      logbackConstantLevels.put("DEBUG", debugField.get(null));
      logbackConstantLevels.put("TRACE", traceField.get(null));
      logbackConstantLevels.put("ALL", allField.get(null));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public String getLogBackLoggerClassName() {
    return new StringBuilder("ch.")
        .append("qos.")
        .append("logback.")
        .append("classic.")
        .append("Logger")
        .toString();
  }

  public String getLogBackLoggerInternalClassName() {
    return getLogBackLoggerClassName().replace('.', '/');
  }

  public Class<?> getLogBackLoggerClass() {
    try {
      return Class.forName(getLogBackLoggerClassName(), true, logbackClassLoader);
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }
}

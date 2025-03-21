package datadog.appsec.benchmark;

import ch.qos.logback.classic.Logger;
import com.datadog.appsec.ddwaf.LibSqreenInitialization;
import org.slf4j.LoggerFactory;

public class BenchmarkUtil {
  private static final org.slf4j.Logger log = LoggerFactory.getLogger(BenchmarkUtil.class);

  public static void disableLogging() {
    org.slf4j.Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    if (root instanceof Logger) {
      Logger logBackRoot = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      logBackRoot.setLevel(ch.qos.logback.classic.Level.OFF);
    }
  }

  public static void initializeWaf() {
    if (!LibSqreenInitialization.WAF) {
      log.info("Waf initialization encountered an error");
    }
  }
}

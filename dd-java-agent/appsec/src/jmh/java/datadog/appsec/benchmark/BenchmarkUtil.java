package datadog.appsec.benchmark;

import ch.qos.logback.classic.Logger;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.exception.AbstractWafException;
import com.datadog.ddwaf.exception.UnsupportedVMException;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.LoggerFactory;

public class BenchmarkUtil {
  public static void disableLogging() {
    org.slf4j.Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    if (root instanceof Logger) {
      Logger logBackRoot = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      logBackRoot.setLevel(ch.qos.logback.classic.Level.OFF);
    }
  }

  public static void initializeWaf() {
    try {
      Waf.initialize(false);
    } catch (AbstractWafException e) {
      throw new UndeclaredThrowableException(e);
    } catch (UnsupportedVMException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

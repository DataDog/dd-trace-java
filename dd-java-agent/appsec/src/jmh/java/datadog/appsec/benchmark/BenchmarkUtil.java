package datadog.appsec.benchmark;

import ch.qos.logback.classic.Logger;
import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import io.sqreen.powerwaf.exception.UnsupportedVMException;
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

  public static void initializePowerwaf() {
    try {
      Powerwaf.initialize(false);
    } catch (AbstractPowerwafException e) {
      throw new UndeclaredThrowableException(e);
    } catch (UnsupportedVMException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

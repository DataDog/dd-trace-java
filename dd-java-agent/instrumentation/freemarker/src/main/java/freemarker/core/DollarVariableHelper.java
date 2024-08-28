package freemarker.core;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariableHelper {
  private DollarVariableHelper() {}

  private static final Logger log = LoggerFactory.getLogger(DollarVariableHelper.class);

  private static final Field AUTO_ESCAPE = prepareAutoEscape();

  private static Field prepareAutoEscape() {
    Field autoEscape = null;
    try {
      autoEscape = DollarVariable.class.getDeclaredField("autoEscape");
      autoEscape.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable autoEscape", e);
      return null;
    }
    return autoEscape;
  }

  public static boolean fetchAutoEscape(DollarVariable dollarVariable) {
    try {
      return (boolean) AUTO_ESCAPE.get(dollarVariable);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

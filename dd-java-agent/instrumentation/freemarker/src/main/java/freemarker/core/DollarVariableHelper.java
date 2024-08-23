package freemarker.core;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariableHelper {
  private DollarVariableHelper() {}

  private static final Logger log = LoggerFactory.getLogger(DollarVariableHelper.class);

  private static final Field AUTO_ESCAPE = prepareAutoEscape();
  private static final Field EXPRESSION = prepareExpression();

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

  private static Field prepareExpression() {
    Field expression = null;
    try {
      expression = DollarVariable.class.getDeclaredField("expression");
      expression.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable expression", e);
      return null;
    }
    return expression;
  }

  public static boolean fetchAutoEscape(DollarVariable dollarVariable) {
    try {
      return (boolean) AUTO_ESCAPE.get(dollarVariable);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static String fetchExpression(DollarVariable dollarVariable) {
    if (EXPRESSION == null) {
      return null;
    }
    try {
      return (String) EXPRESSION.get(dollarVariable);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

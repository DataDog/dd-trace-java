package freemarker.core;

import datadog.trace.api.iast.sink.XssModule;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariable9Helper {
  private DollarVariable9Helper() {}

  private static final Logger log = LoggerFactory.getLogger(DollarVariable9Helper.class);

  private static final Field ESCAPED_EXPRESSION = prepareEscapedExpression();

  private static Field prepareEscapedExpression() {
    Field autoEscape = null;
    try {
      autoEscape = DollarVariable.class.getDeclaredField("escapedExpression");
      autoEscape.setAccessible(true);
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable escapedExpression", e);
      return null;
    }
    return autoEscape;
  }

  public static Expression fetchEscapeExpression(
      DollarVariable dollarVariable, Environment environment, XssModule xssModule) {
    if (ESCAPED_EXPRESSION == null) {
      return null;
    }
    try {
      return (Expression) ESCAPED_EXPRESSION.get(dollarVariable);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

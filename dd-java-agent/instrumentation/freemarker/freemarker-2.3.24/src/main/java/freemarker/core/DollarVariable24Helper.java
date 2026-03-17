package freemarker.core;

import freemarker.template.TemplateException;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariable24Helper {
  private DollarVariable24Helper() {}

  private static final Logger log = LoggerFactory.getLogger(DollarVariable24Helper.class);

  private static final Field AUTO_ESCAPE = prepareAutoEscape();
  private static final Field ESCAPED_EXPRESSION = prepareEscapedExpression();

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

  private static Field prepareEscapedExpression() {
    try {
      Field escapedExpression = DollarVariable.class.getDeclaredField("escapedExpression");
      escapedExpression.setAccessible(true);
      return escapedExpression;
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable escapedExpression", e);
      return null;
    }
  }

  public static boolean fetchAutoEscape(Object dollarVariable) {
    if (AUTO_ESCAPE == null || !(dollarVariable instanceof DollarVariable)) {
      return true;
    }
    try {
      return (boolean) AUTO_ESCAPE.get(dollarVariable);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static String fetchCharSec(Object object, Environment environment) {
    if (!(object instanceof DollarVariable)) {
      return null;
    }
    if (ESCAPED_EXPRESSION != null) {
      try {
        if (ESCAPED_EXPRESSION.get(object) instanceof BuiltInForLegacyEscaping) {
          return null;
        }
      } catch (IllegalAccessException e) {
        throw new UndeclaredThrowableException(e);
      }
    }
    try {
      return (String) ((DollarVariable) object).calculateInterpolatedStringOrMarkup(environment);
    } catch (TemplateException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static Integer fetchBeginLine(Object object) {
    if (!(object instanceof DollarVariable)) {
      return null;
    }
    return ((DollarVariable) object).beginLine;
  }
}

package freemarker.core;

import freemarker.template.TemplateException;
import java.lang.reflect.Field;
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

  /**
   * Returns {@code true} (skip the XSS check) when auto-escaping is on, when the object is not a
   * {@link DollarVariable}, or when the flag cannot be read. Markup values such as {@code ?no_esc}
   * are skipped too (known gap, APPSEC-71792).
   */
  public static boolean fetchAutoEscape(Object dollarVariable) {
    if (AUTO_ESCAPE == null || !(dollarVariable instanceof DollarVariable)) {
      return true;
    }
    try {
      return (boolean) AUTO_ESCAPE.get(dollarVariable);
    } catch (IllegalAccessException e) {
      log.debug("Failed to read DollarVariable autoEscape", e);
      return true;
    }
  }

  /**
   * Returns the interpolated string, or {@code null} when it is not reported: legacy escaping
   * built-in, evaluation error (FreeMarker reports it when it evaluates the interpolation right
   * after) or markup output (known gap, APPSEC-71792).
   */
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
        log.debug("Failed to read DollarVariable escapedExpression", e);
        return null;
      }
    }
    final Object value;
    try {
      value = ((DollarVariable) object).calculateInterpolatedStringOrMarkup(environment);
    } catch (TemplateException e) {
      // user-caused, FreeMarker reports it when evaluating the interpolation
      return null;
    }
    return value instanceof String ? (String) value : null;
  }

  public static Integer fetchBeginLine(Object object) {
    if (!(object instanceof DollarVariable)) {
      return null;
    }
    return ((DollarVariable) object).beginLine;
  }
}

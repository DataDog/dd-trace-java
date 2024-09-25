package freemarker.core;

import freemarker.template.TemplateModelException;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DollarVariable2_3_9Helper {
  private DollarVariable2_3_9Helper() {}

  private static final Logger log = LoggerFactory.getLogger(DollarVariable2_3_9Helper.class);

  private static final Field ESCAPED_EXPRESSION = prepareEscapedExpression();

  private static Field prepareEscapedExpression() {
    try {
      Field autoEscape = DollarVariable.class.getDeclaredField("escapedExpression");
      autoEscape.setAccessible(true);
      return autoEscape;
    } catch (Throwable e) {
      log.debug("Failed to get DollarVariable escapedExpression", e);
      return null;
    }
  }

  public static Expression fetchEscapeExpression(Object object) {
    if (ESCAPED_EXPRESSION == null || !(object instanceof DollarVariable)) {
      return null;
    }
    try {
      return (Expression) ESCAPED_EXPRESSION.get(object);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static String fetchCharSec(Object object, Environment environment) {
    if (!(object instanceof DollarVariable)) {
      return null;
    }
    final Expression expression = DollarVariable2_3_9Helper.fetchEscapeExpression(object);
    if (expression instanceof BuiltIn) {
      return null;
    }
    try {
      return environment.getDataModel().get(expression.toString()).toString();
    } catch (TemplateModelException e) {
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

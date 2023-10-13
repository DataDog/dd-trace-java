package datadog.trace.instrumentation.ratpack;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collection;
import ratpack.path.internal.TokenPathBinder;

public class TokenPathBinderInspector {
  private static final Field TOKEN_NAMES_FIELD;

  static {
    try {
      TOKEN_NAMES_FIELD = TokenPathBinder.class.getDeclaredField("tokenNames");
      TOKEN_NAMES_FIELD.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static boolean hasTokenNames(TokenPathBinder binder) {
    try {
      Collection<?> tokenNames = (Collection<?>) TOKEN_NAMES_FIELD.get(binder);
      return !tokenNames.isEmpty();
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}

package datadog.compiler;

import java.lang.reflect.Field;
import javax.annotation.Nullable;

public class CompilerUtils {
  static final String SOURCE_PATH_INJECTED_FIELD_NAME = "__datadog_sourcePath";

  /** Returns path to class source file (injected by Datadog Java compiler plugin) */
  public static @Nullable String getSourcePath(Class<?> c) {
    try {
      Field sourcePathField = c.getDeclaredField(CompilerUtils.SOURCE_PATH_INJECTED_FIELD_NAME);
      sourcePathField.setAccessible(true);
      return (String) sourcePathField.get(c);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }
}

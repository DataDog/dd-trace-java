package datadog.trace.bootstrap.instrumentation.ci.source;

import java.lang.reflect.Field;
import javax.annotation.Nullable;

// FIXME add documentation
public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  public static final String SOURCE_PATH_FIELD_NAME =
      "__datadog_sourcePath"; // FIXME possible to make a reference to compiler plugin?

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    try {
      return tryGetSourcePath(c);
    } catch (Exception e) {
      return null;
    }
  }

  private String tryGetSourcePath(Class<?> c) throws NoSuchFieldException, IllegalAccessException {
    Field sourceFilePath = c.getDeclaredField(SOURCE_PATH_FIELD_NAME);
    sourceFilePath.setAccessible(true);
    return (String) sourceFilePath.get(c);
  }

  public boolean isSourcePathInfoAvailable(Class<?> c) {
    try {
      return tryGetSourcePath(c) != null;
    } catch (Exception e) {
      return false;
    }
  }
}

package datadog.trace.plugin.csi.util;

import java.io.File;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

public abstract class CallSiteUtils {

  private CallSiteUtils() {}

  public static Type classNameToType(@Nonnull final String className) {
    return Type.getType(classNameToDescriptor(className));
  }

  public static String classNameToDescriptor(@Nonnull final Class<?> clazz) {
    return classNameToDescriptor(clazz.getName());
  }

  public static String classNameToDescriptor(@Nonnull final String className) {
    return "L" + className.replaceAll("\\.", "/") + ";";
  }

  public static String capitalize(final String str) {
    if (str == null || str.isEmpty()) {
      return str;
    }
    if (str.length() == 1) {
      return str.toUpperCase();
    }
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }

  public static void createNewFile(@Nonnull final File file) {
    final File folder = file.getParentFile();
    if (!folder.exists() && !folder.mkdirs()) {
      throw new RuntimeException("Cannot create folder: " + folder);
    }
    deleteFile(file);
    try {
      if (!file.createNewFile()) {
        throw new RuntimeException("Cannot create file: " + file);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void deleteFile(@Nonnull final File file) {
    if (file.exists() && !file.delete()) {
      throw new RuntimeException("Cannot delete file: " + file);
    }
  }

  public static String repeat(@Nonnull final String value, int count) {
    if (count < 0) {
      throw new IllegalArgumentException("count is negative: " + count);
    }
    if (count == 1) {
      return value;
    }
    return IntStream.range(0, count).mapToObj(i -> value).collect(Collectors.joining());
  }

  public static String repeat(final char value, int count) {
    return repeat(Character.toString(value), count);
  }
}

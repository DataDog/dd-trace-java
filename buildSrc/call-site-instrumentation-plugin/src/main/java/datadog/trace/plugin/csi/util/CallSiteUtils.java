package datadog.trace.plugin.csi.util;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
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

  public static URL toURL(final Path path) {
    try {
      URL url = path.toUri().toURL();
      // There's a subtle detail where `URLClassLoader` requires directory URLs to end with '/',
      // otherwise they are assimilated to jar file, and vice versa.
      if (path.toFile().isDirectory() || !path.toString().endsWith(".jar")) {
        String urlString = url.toString();
        if (!urlString.endsWith("/")) {
          url = new URL(urlString + "/");
        }
      }
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}

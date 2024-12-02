package datadog.trace.instrumentation.liberty20;

import com.ibm.ws.classloading.internal.ThreadContextClassLoader;
import java.util.function.Function;
import java.util.function.Supplier;

public class BundleNameHelper {
  private BundleNameHelper() {}

  public static final Function<ClassLoader, Supplier<String>> EXTRACTOR =
      classLoader -> {
        final String id = ((ThreadContextClassLoader) classLoader).getKey();
        // id is something like <type>:name#somethingelse
        final int head = id.indexOf(':');
        if (head < 0) {
          return () -> null;
        }
        final int tail = id.lastIndexOf('#');
        if (tail < 0) {
          return () -> null;
        }
        final String name = id.substring(head + 1, tail);
        return () -> name;
      };
}

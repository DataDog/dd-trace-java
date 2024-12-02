package datadog.trace.instrumentation.tomcat9;

import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.catalina.loader.WebappClassLoaderBase;

public class ContextNameHelper {
  private ContextNameHelper() {}

  public static final Function<ClassLoader, Supplier<String>> ADDER =
      // tomcat does not initialize the context name until the context is started and the
      // classloader is created too early to cache that value
      classLoader -> ((WebappClassLoaderBase) classLoader)::getContextName;
}

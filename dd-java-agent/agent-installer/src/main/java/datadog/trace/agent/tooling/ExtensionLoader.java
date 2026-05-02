package datadog.trace.agent.tooling;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads extensions into the Datadog tracer. */
public final class ExtensionLoader {
  private static final Logger log = LoggerFactory.getLogger(ExtensionLoader.class);

  private static final String[] NO_EXTENSIONS = {};

  /** Loads extensions from the extended classloader built by {@link ExtensionFinder}. */
  public static <T> List<T> loadExtensions(Class<T> extensionType) {
    return loadExtensions(Utils.getExtendedClassLoader(), extensionType);
  }

  private static <T> List<T> loadExtensions(ClassLoader classLoader, Class<T> extensionType) {
    List<T> extensions = new ArrayList<>();
    for (String className : listExtensionNames(classLoader, extensionType)) {
      try {
        @SuppressWarnings("unchecked")
        Class<T> extensionClass = (Class<T>) classLoader.loadClass(className);
        extensions.add(extensionClass.getConstructor().newInstance());
        log.debug("Loaded extension {}", className);
      } catch (Throwable e) {
        log.warn("Failed to load extension {}", className, e);
      }
    }
    return extensions;
  }

  /** Returns the class names listed under the extension's {@code META-INF/services} descriptor. */
  private static String[] listExtensionNames(ClassLoader classLoader, Class<?> extensionType) {
    try {
      Set<String> lines = new LinkedHashSet<>();
      Enumeration<URL> urls =
          classLoader.getResources("META-INF/services/" + extensionType.getName());
      while (urls.hasMoreElements()) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(urls.nextElement().openStream(), UTF_8))) {
          String line = reader.readLine();
          while (line != null) {
            lines.add(line);
            line = reader.readLine();
          }
        }
      }
      return lines.toArray(new String[0]);
    } catch (Throwable e) {
      log.warn("Problem reading extensions descriptor", e);
      return NO_EXTENSIONS;
    }
  }
}

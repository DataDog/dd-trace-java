package datadog.trace.agent.tooling;

import static java.nio.charset.StandardCharsets.UTF_8;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads trace extensions from a comma-separated list of jars, or directories containing jars. */
public final class ExtensionsLoader {
  private static final Logger log = LoggerFactory.getLogger(ExtensionsLoader.class);

  private static final String DATADOG_MODULE_EXTENSION_ID =
      "datadog.trace.agent.tooling.InstrumenterModule";

  private static final String[] NO_EXTENSIONS = {};

  private final ClassLoader extensionLoader;

  public ExtensionsLoader(String extensionsPath) {
    extensionLoader =
        new URLClassLoader(toURLs(extensionsPath), Instrumenter.class.getClassLoader());
  }

  public List<InstrumenterModule> loadModules() {
    List<InstrumenterModule> modules = new ArrayList<>();
    for (String className : discoverExtensions(extensionLoader, DATADOG_MODULE_EXTENSION_ID)) {
      try {
        modules.add(loadDatadogModule(className));
      } catch (Throwable e) {
        log.warn("Failed to load extension module {}", className, e);
      }
    }
    return modules;
  }

  private InstrumenterModule loadDatadogModule(String className)
      throws ReflectiveOperationException {
    Class<?> moduleClass = extensionLoader.loadClass(className);
    return (InstrumenterModule) moduleClass.getConstructor().newInstance();
  }

  /** Similar to {@link java.util.ServiceLoader} but doesn't load the discovered extensions. */
  private static String[] discoverExtensions(ClassLoader loader, String extensionId) {
    try {
      Set<String> lines = new LinkedHashSet<>();
      Enumeration<URL> urls = loader.getResources("META-INF/services/" + extensionId);
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

  @SuppressForbidden // split on single-character uses fast path
  private static URL[] toURLs(String path) {
    List<URL> urls = new ArrayList<>();
    for (String entry : path.split(",")) {
      File file = new File(entry);
      if (file.isDirectory()) {
        visitDirectory(file, urls);
      } else if (isJar(file)) {
        addExtensionJar(file, urls);
      }
    }
    return urls.toArray(new URL[0]);
  }

  private static void visitDirectory(File dir, List<URL> urls) {
    File[] files = dir.listFiles(ExtensionsLoader::isJar);
    if (null != files) {
      for (File file : files) {
        addExtensionJar(file, urls);
      }
    }
  }

  private static void addExtensionJar(File file, List<URL> urls) {
    try {
      urls.add(file.toURI().toURL());
    } catch (MalformedURLException e) {
      log.debug("Ignoring extension jar {}", file, e);
    }
  }

  private static boolean isJar(File file) {
    return file.getName().endsWith(".jar") && file.isFile();
  }
}

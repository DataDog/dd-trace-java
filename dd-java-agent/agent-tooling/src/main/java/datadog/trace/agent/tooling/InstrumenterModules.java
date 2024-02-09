package datadog.trace.agent.tooling;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides a sorted sequence of {@link InstrumenterModule}s. */
public final class InstrumenterModules {
  static final Logger log = LoggerFactory.getLogger(InstrumenterModules.class);

  public static Iterable<InstrumenterModule> load(ClassLoader loader) {
    List<InstrumenterModule> modules = new ArrayList<>();
    for (String moduleName : loadModuleNames(loader)) {
      try {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<InstrumenterModule> moduleType = (Class) loader.loadClass(moduleName);
        modules.add(moduleType.getConstructor().newInstance());
      } catch (Throwable e) {
        log.error("Failed to load - InstrumenterModule={}", moduleName, e);
      }
    }
    modules.sort(Comparator.comparing(InstrumenterModule::order));
    return modules;
  }

  private static String[] loadModuleNames(ClassLoader loader) {
    Set<String> lines = new LinkedHashSet<>();
    try {
      Enumeration<URL> urls =
          loader.getResources("META-INF/services/datadog.trace.agent.tooling.InstrumenterModule");
      while (urls.hasMoreElements()) {
        try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
          String line = reader.readLine();
          while (line != null) {
            lines.add(line);
            line = reader.readLine();
          }
        }
      }
    } catch (Throwable e) {
      log.error("Failed to load - InstrumenterModule list", e);
    }
    return lines.toArray(new String[0]);
  }
}

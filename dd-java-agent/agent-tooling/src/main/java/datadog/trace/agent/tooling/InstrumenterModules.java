package datadog.trace.agent.tooling;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a stable sequence of {@link InstrumenterModule}s registered as META-INF services. The id
 * of the instrumentation currently being installed is available during iteration by calling {@link
 * #currentInstrumentationId()}.
 *
 * <p>Note: it is expected that only one thread will iterate over this sequence at a time.
 */
public final class InstrumenterModules implements Iterable<InstrumenterModule> {
  static final Logger log = LoggerFactory.getLogger(InstrumenterModules.class);

  final ClassLoader loader;
  final String[] names;

  final InstrumenterModule[] modules;
  static int currentInstrumentationId;

  public static InstrumenterModules load() {
    ClassLoader loader = InstrumenterModule.class.getClassLoader();
    return new InstrumenterModules(loader, loadModuleNames(loader));
  }

  InstrumenterModules(ClassLoader loader, String[] names) {
    this.loader = loader;
    this.names = names;

    this.modules = new InstrumenterModule[names.length];
  }

  public int maxInstrumentationId() {
    return modules.length;
  }

  /** Returns the id of the instrumentation currently being installed. */
  public static int currentInstrumentationId() {
    return currentInstrumentationId;
  }

  @Override
  public Iterator<InstrumenterModule> iterator() {
    return new Iterator<InstrumenterModule>() {
      private int index = 0;

      @Override
      public boolean hasNext() {
        while (index < modules.length) {
          if (null != modules[index]) {
            currentInstrumentationId = index;
            return true;
          }
          String nextName = names[index];
          if (null != nextName) {
            try {
              currentInstrumentationId = index; // set before loading the next module
              @SuppressWarnings({"rawtypes", "unchecked"})
              Class<InstrumenterModule> nextType = (Class) loader.loadClass(nextName);
              modules[index] = nextType.getConstructor().newInstance();
              return true;
            } catch (Throwable e) {
              log.error("Failed to load - instrumentation.class={}", nextName, e);
              names[index] = null; // only attempt to load each module once
            }
          }
          index++;
        }
        return false;
      }

      @Override
      public InstrumenterModule next() {
        if (hasNext()) {
          return modules[index++];
        } else {
          throw new NoSuchElementException();
        }
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static String[] loadModuleNames(ClassLoader loader) {
    Set<String> lines = new LinkedHashSet<>();
    try {
      // className has to be a separate variable for shadowing to work
      String className = "datadog.trace.agent.tooling.InstrumenterModule";
      Enumeration<URL> urls = loader.getResources("META-INF/services/" + className);
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
      log.error("Failed to load - instrumentation.class list", e);
    }
    return lines.toArray(new String[0]);
  }
}

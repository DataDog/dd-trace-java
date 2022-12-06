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
 * Provides a stable sequence of {@link Instrumenters} registered as META-INF services. The id of
 * the {@link Instrumenter} currently being installed is available during iteration by calling
 * {@link #currentInstrumentationId()}.
 *
 * <p>Note: it is expected that only one thread will iterate over instrumenters at a time.
 */
public final class Instrumenters implements Iterable<Instrumenter> {
  static final Logger log = LoggerFactory.getLogger(Instrumenters.class);

  final ClassLoader loader;
  final String[] names;

  final Instrumenter[] instrumenters;
  static int currentInstrumentationId;

  public static Instrumenters load(ClassLoader loader) {
    return new Instrumenters(loader, loadInstrumenterNames(loader));
  }

  Instrumenters(ClassLoader loader, String[] names) {
    this.loader = loader;
    this.names = names;

    this.instrumenters = new Instrumenter[names.length];
  }

  public int maxInstrumentationId() {
    return instrumenters.length;
  }

  /** Returns the id of the {@link Instrumenter} currently being installed. */
  public static int currentInstrumentationId() {
    return currentInstrumentationId;
  }

  @Override
  public Iterator<Instrumenter> iterator() {
    return new Iterator<Instrumenter>() {
      private int index = 0;

      @Override
      public boolean hasNext() {
        while (index < instrumenters.length) {
          if (null != instrumenters[index]) {
            currentInstrumentationId = index;
            return true;
          }
          String nextName = names[index];
          if (null != nextName) {
            try {
              currentInstrumentationId = index; // set before loading instrumenter
              @SuppressWarnings({"rawtypes", "unchecked"})
              Class<Instrumenter> nextType = (Class) loader.loadClass(nextName);
              instrumenters[index] = nextType.getConstructor().newInstance();
              return true;
            } catch (Throwable e) {
              log.error("Failed to load - instrumentation.class={}", nextName, e);
              names[index] = null; // only attempt to load instrumenter once
            }
          }
          index++;
        }
        return false;
      }

      @Override
      public Instrumenter next() {
        if (hasNext()) {
          return instrumenters[index++];
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

  private static String[] loadInstrumenterNames(ClassLoader loader) {
    Set<String> lines = new LinkedHashSet<>();
    try {
      Enumeration<URL> urls =
          loader.getResources("META-INF/services/datadog.trace.agent.tooling.Instrumenter");
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

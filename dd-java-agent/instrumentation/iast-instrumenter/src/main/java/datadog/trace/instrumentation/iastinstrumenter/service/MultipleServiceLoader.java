package datadog.trace.instrumentation.iastinstrumenter.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class MultipleServiceLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(MultipleServiceLoader.class);

  private MultipleServiceLoader() {}

  public static <E> List<E> load(final ClassLoader classLoader, final Class<?>... spiInterfaces) {
    if (spiInterfaces == null || spiInterfaces.length == 0) {
      return Collections.emptyList();
    }
    try {
      List<E> services = new ArrayList<>();
      for (String site : loadServiceNames(classLoader, spiInterfaces)) {
        try {
          @SuppressWarnings({"rawtypes", "unchecked"})
          Class<E> moduleType = (Class) classLoader.loadClass(site);
          services.add(moduleType.getConstructor().newInstance());
        } catch (Throwable e) {
          LOGGER.error("Failed to load call site {}", site, e);
        }
      }
      return services;
    } catch (final IOException e) {
      throw new UncheckedIOException("Problem loading call sites", e);
    }
  }

  private static String[] loadServiceNames(
      final ClassLoader loader, final Class<?>... spiInterfaces) throws IOException {
    Set<String> lines = new LinkedHashSet<>();
    for (final Class<?> spi : spiInterfaces) {
      Enumeration<URL> urls =
          loader.getResources(String.format("META-INF/services/%s", spi.getName()));
      while (urls.hasMoreElements()) {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(urls.nextElement().openStream(), UTF_8))) {
          String line = reader.readLine();
          while (line != null) {
            line = line.trim();
            if (!line.isEmpty()) {
              lines.add(line);
            }
            line = reader.readLine();
          }
        }
      }
    }
    return lines.toArray(new String[0]);
  }
}

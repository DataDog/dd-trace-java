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

public abstract class CallSitesLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(CallSitesLoader.class);
  private static final int CALL_SITE_COUNT = 64;

  private CallSitesLoader() {}

  public static <E> List<E> load(final ClassLoader classLoader, final Class<?>... spiInterfaces) {
    if (spiInterfaces == null || spiInterfaces.length == 0) {
      return Collections.emptyList();
    }
    try {
      List<E> services = new ArrayList<>(CALL_SITE_COUNT);
      for (String site : loadServiceNames(classLoader, spiInterfaces)) {
        try {
          @SuppressWarnings({"rawtypes", "unchecked"})
          Class<E> moduleType = (Class) classLoader.loadClass(site);
          services.add(moduleType.getConstructor().newInstance());
        } catch (Throwable e) {
          LOGGER.error("Failed to load call site {}", site, e);
        }
      }
      if (services.size() > CALL_SITE_COUNT) {
        LOGGER.debug(
            "Call site count has gone over the expected threshold CALL_SITE_COUNT={}, consider setting a bigger value",
            CALL_SITE_COUNT);
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
      Enumeration<URL> urls = loader.getResources("META-INF/services/" + spi.getName());
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

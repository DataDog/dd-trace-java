package datadog.trace.agent.tooling;

import static datadog.opentelemetry.tooling.OtelExtensionHandler.OPENTELEMETRY;
import static datadog.trace.agent.tooling.ExtensionHandler.DATADOG;

import datadog.trace.api.telemetry.OtelSpiCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.dynamic.loading.MultipleParentClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finds extensions to the Datadog tracer. */
public final class ExtensionFinder {
  private static final Logger log = LoggerFactory.getLogger(ExtensionFinder.class);

  private static final ExtensionHandler[] handlers = {OPENTELEMETRY, DATADOG};

  private static final String EXTENSIONS_PATH_SOURCE = "extensions_path";
  private static final String SERVICES_PREFIX = "META-INF/services/";

  private static final String[] OTEL_SPI_FQNS = {
    "io.opentelemetry.context.ContextStorageProvider",
    "io.opentelemetry.exporter.internal.compression.CompressorProvider",
    "io.opentelemetry.exporter.internal.grpc.GrpcSenderProvider",
    "io.opentelemetry.exporter.internal.http.HttpSenderProvider",
    "io.opentelemetry.javaagent.extension.AgentListener",
    "io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer",
    "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
    "io.opentelemetry.javaagent.tooling.BeforeAgentListener",
    "io.opentelemetry.javaagent.tooling.LoggingCustomizer",
    "io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer",
    "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigureListener",
    "io.opentelemetry.sdk.autoconfigure.spi.ConditionalResourceProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.logs.ConfigurableLogRecordExporterProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.metrics.ConfigurableMetricExporterProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider",
    "io.opentelemetry.sdk.autoconfigure.spi.traces.SpanExporterCustomizer",
  };

  /**
   * Discovers extensions on the configured path and creates a classloader for each extension.
   * Registers the combined classloader with {@link Utils#setExtendedClassLoader(ClassLoader)}.
   *
   * @return {@code true} if any extensions were found
   */
  public static boolean findExtensions(String extensionsPath, Class<?>... extensionTypes) {
    List<ClassLoader> classLoaders = new ArrayList<>();
    List<JarFile> unusedJars = new ArrayList<>();

    ClassLoader parent = Utils.getAgentClassLoader();
    String[] descriptors = descriptors(extensionTypes);

    for (JarFile jar : findExtensionJars(extensionsPath)) {
      recordOtelSpiTelemetry(jar);
      URL extensionURL = findExtensionURL(jar, descriptors);
      if (null != extensionURL) {
        log.debug("Found extension jar {}", jar.getName());
        classLoaders.add(new URLClassLoader(new URL[] {extensionURL}, parent));
      } else {
        unusedJars.add(jar);
      }
    }

    close(unusedJars);

    if (classLoaders.size() > 1) {
      Utils.setExtendedClassLoader(new MultipleParentClassLoader(classLoaders));
    } else if (!classLoaders.isEmpty()) {
      Utils.setExtendedClassLoader(classLoaders.get(0));
    }

    return !classLoaders.isEmpty();
  }

  /** Reports telemetry for any tracked OpenTelemetry SPI service descriptors present in the jar. */
  static void recordOtelSpiTelemetry(JarFile jar) {
    for (String fqn : OTEL_SPI_FQNS) {
      if (null != jar.getJarEntry(SERVICES_PREFIX + fqn)) {
        OtelSpiCollector.getInstance().recordSpiDetected(fqn, EXTENSIONS_PATH_SOURCE);
      }
    }
  }

  /** Closes jar resources from the extension path which did not contain any extensions. */
  private static void close(List<JarFile> unusedJars) {
    for (JarFile jar : unusedJars) {
      try {
        jar.close();
      } catch (Exception ignore) {
        // move onto next jar
      }
    }
  }

  /**
   * Uses the registered {@link ExtensionHandler}s to find jars containing matching extensions.
   * Creates a URL that uses the matched extension handler to access content from the extension.
   */
  private static URL findExtensionURL(JarFile jar, String[] descriptors) {
    for (ExtensionHandler handler : handlers) {
      for (String descriptor : descriptors) {
        if (null != handler.mapEntry(jar, descriptor)) {
          return buildExtensionURL(jar, handler);
        }
      }
    }
    return null;
  }

  /** Builds a URL that uses an {@link ExtensionHandler} to access the extension. */
  private static URL buildExtensionURL(JarFile jar, ExtensionHandler handler) {
    try {
      return new URL("dd-ext", null, -1, "/", new StreamMapper(jar, handler));
    } catch (MalformedURLException ignore) {
      return null;
    }
  }

  /** Uses a {@link ExtensionHandler} to map and stream content from the extension. */
  static final class StreamMapper extends URLStreamHandler {
    private final JarFile jar;
    private final ExtensionHandler handler;

    StreamMapper(JarFile jar, ExtensionHandler handler) {
      this.jar = jar;
      this.handler = handler;
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
      String file = url.getFile();
      if (!file.isEmpty() && file.charAt(0) == '/') {
        file = file.substring(1);
      }
      JarEntry jarEntry = handler.mapEntry(jar, file);
      if (null != jarEntry) {
        return handler.mapContent(url, jar, jarEntry);
      } else {
        throw new FileNotFoundException("JAR entry " + file + " not found in " + jar.getName());
      }
    }
  }

  @SuppressForbidden // split on single-character uses fast path
  private static List<JarFile> findExtensionJars(String extensionsPath) {
    List<JarFile> extensionJars = new ArrayList<>();
    for (String entry : extensionsPath.split(",")) {
      File file = new File(entry);
      if (file.isDirectory()) {
        visitDirectory(file, extensionJars);
      } else if (isJar(file)) {
        addExtensionJar(file, extensionJars);
      }
    }
    return extensionJars;
  }

  private static void visitDirectory(File dir, List<JarFile> extensionJars) {
    File[] files = dir.listFiles(ExtensionFinder::isJar);
    if (null != files) {
      for (File file : files) {
        addExtensionJar(file, extensionJars);
      }
    }
  }

  private static void addExtensionJar(File file, List<JarFile> extensionJars) {
    try {
      extensionJars.add(new JarFile(file, false));
    } catch (Exception e) {
      log.debug("Problem reading extension jar {}", file, e);
    }
  }

  /** The {@code META-INF/service} descriptors to look for. */
  private static String[] descriptors(Class<?>[] extensionTypes) {
    String[] descriptors = new String[extensionTypes.length];
    for (int i = 0; i < extensionTypes.length; i++) {
      descriptors[i] = "META-INF/services/" + extensionTypes[i].getName();
    }
    return descriptors;
  }

  private static boolean isJar(File file) {
    return file.getName().endsWith(".jar") && file.isFile();
  }
}

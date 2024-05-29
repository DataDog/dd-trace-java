package datadog.opentelemetry.tooling;

import datadog.trace.agent.tooling.ExtensionHandler;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Handles OpenTelemetry instrumentations, so they can be loaded into the Datadog tracer. */
public final class OtelExtensionHandler extends ExtensionHandler {

  /** Handler for loading externally built OpenTelemetry extensions. */
  public static final OtelExtensionHandler OPENTELEMETRY = new OtelExtensionHandler();

  private static final String OPENTELEMETRY_MODULE_DESCRIPTOR =
      "META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule";

  private static final String DATADOG_MODULE_DESCRIPTOR =
      "META-INF/services/datadog.trace.agent.tooling.InstrumenterModule";

  @Override
  public JarEntry mapEntry(JarFile jar, String file) {
    if (DATADOG_MODULE_DESCRIPTOR.equals(file)) {
      // redirect request to include OpenTelemetry instrumentations
      return super.mapEntry(jar, OPENTELEMETRY_MODULE_DESCRIPTOR);
    } else {
      return super.mapEntry(jar, file);
    }
  }

  @Override
  public URLConnection mapContent(URL url, JarFile jar, JarEntry entry) {
    if (entry.getName().endsWith(".class")) {
      return new ClassMappingConnection(url, jar, entry, OtelInstrumentationMapper::new);
    } else {
      return new JarFileConnection(url, jar, entry);
    }
  }
}

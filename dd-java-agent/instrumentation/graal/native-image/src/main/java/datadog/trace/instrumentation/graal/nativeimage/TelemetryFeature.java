package datadog.trace.instrumentation.graal.nativeimage;

import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyResolver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.graalvm.nativeimage.hosted.Feature;

public final class TelemetryFeature implements Feature {

  private static final Set<URI> SEEN_URIS = new HashSet<>();

  public static InputStream getDependenciesFileContent(final DuringAnalysisAccess access) {
    final Set<Dependency> dependencies = new HashSet<>();
    final Set<ProtectionDomain> seen = new HashSet<>();
    for (final Class<?> clazz : access.reachableSubtypes(Object.class)) {
      final ProtectionDomain protectionDomain = clazz.getProtectionDomain();
      if (protectionDomain == null) {
        continue;
      }
      if (!seen.add(protectionDomain)) {
        continue;
      }
      final CodeSource codeSource = protectionDomain.getCodeSource();
      if (codeSource == null) {
        continue;
      }
      final URL location = codeSource.getLocation();
      if (location == null) {
        continue;
      }
      final URI uri = convertToURI(location);
      if (uri == null) {
        continue;
      }
      if (!SEEN_URIS.add(uri)) {
        continue;
      }
      dependencies.addAll(DependencyResolver.resolve(uri));
    }
    if (dependencies.isEmpty()) {
      return null;
    }
    final List<String> serializedDependencies = new ArrayList<>(dependencies.size());
    for (final Dependency dependency : dependencies) {
      serializedDependencies.add(dependency.toSimpleString());
    }
    serializedDependencies.sort(String::compareTo);
    final byte[] content =
        String.join("\n", serializedDependencies).getBytes(StandardCharsets.UTF_8);
    return new ByteArrayInputStream(content);
  }

  private static URI convertToURI(URL location) {
    if (location == null) {
      return null;
    }
    if (location.getProtocol().equals("vfs")) {
      // TODO: Ignore JBoss VFS in native image at the moment, since it's not tested.
      return null;
    }
    try {
      return new URI(location.toString().replace(" ", "%20"));
    } catch (URISyntaxException e) {
      // silently ignored
    }
    return null;
  }
}

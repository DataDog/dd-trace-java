package datadog.opentelemetry.tooling;

import static datadog.opentelemetry.tooling.OtelShimInjector.OTEL_CONTEXT_CLASSES;
import static datadog.opentelemetry.tooling.OtelShimInjector.OTEL_CONTEXT_STORAGE_CLASSES;
import static datadog.opentelemetry.tooling.OtelShimInjector.OTEL_ENTRYPOINT_CLASSES;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Bytebuddy gradle plugin which injects our OpenTelemetry shim into the target API.
 *
 * @see "buildSrc/src/main/groovy/InstrumentPlugin.groovy"
 */
public class OtelShimGradlePlugin extends Plugin.ForElementMatcher {
  private final File targetDir;

  @SuppressForbidden
  public OtelShimGradlePlugin(File targetDir) {
    super(
        namedOneOf(OTEL_ENTRYPOINT_CLASSES)
            .or(namedOneOf(OTEL_CONTEXT_STORAGE_CLASSES))
            .or(namedOneOf(OTEL_CONTEXT_CLASSES)));
    this.targetDir = targetDir;
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(OtelShimInjector.INSTANCE);
  }

  @Override
  public void close() throws IOException {}
}

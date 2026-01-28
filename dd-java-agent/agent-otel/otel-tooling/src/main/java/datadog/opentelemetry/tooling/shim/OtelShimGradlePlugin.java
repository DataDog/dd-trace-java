package datadog.opentelemetry.tooling.shim;

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
 * @see datadog.gradle.plugin.instrument.BuildTimeInstrumentationPlugin
 */
public class OtelShimGradlePlugin extends Plugin.ForElementMatcher {
  private final File targetDir;

  static final String[] OTEL_SHIM_INJECTED_CLASSES = {
    "io.opentelemetry.api.DefaultOpenTelemetry",
    "io.opentelemetry.api.GlobalOpenTelemetry$ObfuscatedOpenTelemetry",
    "io.opentelemetry.context.ThreadLocalContextStorage",
    "io.opentelemetry.context.StrictContextStorage",
    "io.opentelemetry.context.ArrayBasedContext",
  };

  @SuppressForbidden
  public OtelShimGradlePlugin(File targetDir) {
    super(namedOneOf(OTEL_SHIM_INJECTED_CLASSES));
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

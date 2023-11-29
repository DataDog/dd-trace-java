package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.oracle.svm.core.jdk.Resources;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import org.graalvm.nativeimage.hosted.Feature;

@AutoService(Instrumenter.class)
public final class ResourcesFeatureInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.ResourcesFeature";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("beforeAnalysis")),
        ResourcesFeatureInstrumentation.class.getName() + "$InjectResourcesAdvice");
    if (InstrumenterConfig.get().isTelemetryEnabledAtBuildTime()) {
      transformation.applyAdvice(
          isMethod()
              .and(
                  named("duringAnalysis")
                      .and(
                          takesArguments(1)
                              .and(
                                  takesArgument(
                                      0,
                                      named(
                                          "org.graalvm.nativeimage.hosted.Feature$DuringAnalysisAccess"))))),
          ResourcesFeatureInstrumentation.class.getName() + "$DependencyResolutionAdvice");
    }
  }

  public static class InjectResourcesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      // the tracer jar is not listed on the image classpath, so manually inject its resources
      // (drop trace/shared prefixes from embedded resources, so we can find them in native-image
      // as the final executable won't have our isolating class-loader to map these resources)

      String[] tracerResources = {
        "dd-java-agent.version",
        "dd-trace-api.version",
        "trace/dd-trace-core.version",
        "shared/dogstatsd/version.properties",
        "shared/datadog/okhttp3/internal/publicsuffix/publicsuffixes.gz"
      };

      for (String original : tracerResources) {
        String flattened = original.substring(original.indexOf('/') + 1);
        try (InputStream is = ClassLoader.getSystemResourceAsStream(original)) {
          Resources.registerResource(flattened, is);
        } catch (Throwable ignore) {
        }
      }
    }
  }

  public static class DependencyResolutionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(final @Advice.Argument(0) Feature.DuringAnalysisAccess access) {
      // This needs to during analysis, once reachability data is found. It is illegal to add new
      // resources after analysis. So this might trigger additional analysis iterations, and we
      // might need to create multiple resource files.
      final InputStream data = TelemetryFeature.getDependenciesFileContent(access);
      if (data == null) {
        return;
      }
      Resources.registerResource("dd-java-agent.dependencies", data);
      access.requireAnalysisIteration();
    }
  }
}

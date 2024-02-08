package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.oracle.svm.core.jdk.Resources;
import datadog.trace.agent.tooling.Instrumenter;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ResourcesFeatureInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.ResourcesFeature";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("beforeAnalysis")),
        ResourcesFeatureInstrumentation.class.getName() + "$InjectResourcesAdvice");
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
        "shared/version-utils.version",
        "shared/datadog/okhttp3/internal/publicsuffix/publicsuffixes.gz",
        "profiling/jfr/dd.jfp",
        "profiling/jfr/safepoints.jfp",
        "profiling/jfr/overrides/comprehensive.jfp",
        "profiling/jfr/overrides/minimal.jfp"
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
}

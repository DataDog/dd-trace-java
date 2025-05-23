package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.oracle.svm.core.jdk.Resources;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class ResourcesFeatureInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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

      List<String> tracerResources = new ArrayList<>();
      tracerResources.add("dd-java-agent.version");
      tracerResources.add("dd-trace-api.version");
      tracerResources.add("trace/dd-trace-core.version");
      tracerResources.add("shared/dogstatsd/version.properties");
      tracerResources.add("shared/version-utils.version");
      tracerResources.add("shared/datadog/okhttp3/internal/publicsuffix/publicsuffixes.gz");
      tracerResources.add("profiling/jfr/dd.jfp");
      tracerResources.add("profiling/jfr/safepoints.jfp");
      tracerResources.add("profiling/jfr/overrides/comprehensive.jfp");
      tracerResources.add("profiling/jfr/overrides/minimal.jfp");

      // jmxfetch configs
      tracerResources.add("metrics/project.properties");
      tracerResources.add("metrics/org/datadog/jmxfetch/default-jmx-metrics.yaml");
      tracerResources.add("metrics/org/datadog/jmxfetch/new-gc-default-jmx-metrics.yaml");
      tracerResources.add("metrics/org/datadog/jmxfetch/old-gc-default-jmx-metrics.yaml");

      // tracer's jmxfetch configs
      tracerResources.add("metrics/jmxfetch-config.yaml");
      tracerResources.add("metrics/jmxfetch-websphere-config.yaml");

      // jmxfetch integrations metricconfigs
      String metricConfigsPath = "metrics/datadog/trace/agent/jmxfetch/";
      String metricConfigs = metricConfigsPath + "metricconfigs.txt";
      tracerResources.add(metricConfigs);
      try (InputStream is = ClassLoader.getSystemResourceAsStream(metricConfigs);
          BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
        String metricConfig;
        while ((metricConfig = reader.readLine()) != null) {
          if (!metricConfig.trim().isEmpty()) {
            tracerResources.add(metricConfigsPath + "metricconfigs/" + metricConfig);
          }
        }
      } catch (Throwable ignore) {
      }

      // registering tracer resources to include in the native build
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

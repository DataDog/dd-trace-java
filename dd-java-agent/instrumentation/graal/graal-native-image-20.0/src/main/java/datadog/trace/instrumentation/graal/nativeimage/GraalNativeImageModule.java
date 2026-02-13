package datadog.trace.instrumentation.graal.nativeimage;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.ArrayList;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class GraalNativeImageModule extends AbstractNativeImageModule {

  // Conditionally include JMXFetch substitutions only when JMXFetch is on the classpath.
  // See AnnotationSubstitutionProcessorInstrumentation for detailed explanation of why this is needed.
  private static final boolean JMXFETCH_PRESENT = isJmxFetchPresent();

  private static boolean isJmxFetchPresent() {
    try {
      Class.forName("org.datadog.jmxfetch.App", false, GraalNativeImageModule.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public String[] helperClassNames() {
    List<String> helpers = new ArrayList<>();
    helpers.add(packageName + ".DeleteFieldAdvice");
    helpers.add(packageName + ".Target_com_datadog_profiling_agent_ProcessContext");
    helpers.add(packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess");

    // Only include JMXFetch substitutions if JMXFetch is actually present
    if (JMXFETCH_PRESENT) {
      helpers.add(packageName + ".Target_org_datadog_jmxfetch_App");
      helpers.add(packageName + ".Target_org_datadog_jmxfetch_Status");
      helpers.add(packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter");
    }

    return helpers.toArray(new String[0]);
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    // JVMCI classes which are part of GraalVM but aren't available in public repositories
    List<String> ignored = new ArrayList<>();
    ignored.add("jdk.vm.ci.meta.ResolvedJavaType");
    ignored.add("jdk.vm.ci.meta.ResolvedJavaField");
    // ignore helper class names as usual
    ignored.add(packageName + ".Target_com_datadog_profiling_agent_ProcessContext");
    ignored.add(packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess");

    // Only include JMXFetch substitutions if JMXFetch is actually present
    if (JMXFETCH_PRESENT) {
      ignored.add(packageName + ".Target_org_datadog_jmxfetch_App");
      ignored.add(packageName + ".Target_org_datadog_jmxfetch_Status");
      ignored.add(packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter");
    }

    return ignored.toArray(new String[0]);
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AnnotationSubstitutionProcessorInstrumentation(),
        new LinkAtBuildTimeInstrumentation(),
        new NativeImageGeneratorRunnerInstrumentation(),
        new ResourcesFeatureInstrumentation());
  }
}

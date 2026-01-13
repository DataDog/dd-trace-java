package datadog.trace.instrumentation.graal.nativeimage;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class GraalNativeImageModule extends AbstractNativeImageModule {
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DeleteFieldAdvice",
      packageName + ".Target_com_datadog_profiling_agent_ProcessContext",
      packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess",
      packageName + ".Target_org_datadog_jmxfetch_App",
      packageName + ".Target_org_datadog_jmxfetch_Status",
      packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter"
    };
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    // JVMCI classes which are part of GraalVM but aren't available in public repositories
    return new String[] {
      "jdk.vm.ci.meta.ResolvedJavaType",
      "jdk.vm.ci.meta.ResolvedJavaField",
      // ignore helper class names as usual
      packageName + ".Target_com_datadog_profiling_agent_ProcessContext",
      packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess",
      packageName + ".Target_org_datadog_jmxfetch_App",
      packageName + ".Target_org_datadog_jmxfetch_Status",
      packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter",
    };
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

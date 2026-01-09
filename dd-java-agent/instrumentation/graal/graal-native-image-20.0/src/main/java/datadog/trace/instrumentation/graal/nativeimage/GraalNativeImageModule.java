package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class GraalNativeImageModule extends InstrumenterModule implements ExcludeFilterProvider {
  public GraalNativeImageModule() {
    super("native-image");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return Platform.isNativeImageBuilder();
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DeleteFieldAdvice",
      packageName + ".Target_com_datadog_profiling_agent_ProcessContext",
      packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess",
      packageName + ".Target_org_datadog_jmxfetch_App",
      packageName + ".Target_org_datadog_jmxfetch_Status",
      packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter",
      "datadog.trace.agent.tooling.nativeimage.TracerActivation",
    };
  }

  @Override
  public boolean injectHelperDependencies() {
    return true;
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
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE, singletonList("com.oracle.svm.core.thread.VMOperationControl$VMOperationThread"));
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AnnotationSubstitutionProcessorInstrumentation(),
        new LinkAtBuildTimeInstrumentation(),
        new NativeImageGeneratorRunnerInstrumentation(),
        new ResourcesFeatureInstrumentation(),
        new VMRuntimeInstrumentation());
  }
}

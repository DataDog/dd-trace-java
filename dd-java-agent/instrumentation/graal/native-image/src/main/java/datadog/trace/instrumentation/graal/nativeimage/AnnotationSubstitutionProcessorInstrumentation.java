package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class AnnotationSubstitutionProcessorInstrumentation
    extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("lookup"))
            .and(takesArgument(0, named("jdk.vm.ci.meta.ResolvedJavaField"))),
        packageName + ".DeleteFieldAdvice");
    transformer.applyAdvice(
        isMethod().and(named("findTargetClasses")),
        AnnotationSubstitutionProcessorInstrumentation.class.getName()
            + "$FindTargetClassesAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Target_datadog_jctools_counters_FixedSizeStripedLongCounterFields",
      packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess",
      packageName + ".Target_org_datadog_jmxfetch_App",
      packageName + ".Target_org_datadog_jmxfetch_Status",
      packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter",
    };
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    // JVMCI classes which are part of GraalVM but aren't available in public repositories
    return new String[] {
      "jdk.vm.ci.meta.ResolvedJavaType",
      "jdk.vm.ci.meta.ResolvedJavaField",
      // ignore helper class names as usual
      packageName + ".Target_datadog_jctools_counters_FixedSizeStripedLongCounterFields",
      packageName + ".Target_datadog_jctools_util_UnsafeRefArrayAccess",
      packageName + ".Target_org_datadog_jmxfetch_App",
      packageName + ".Target_org_datadog_jmxfetch_Status",
      packageName + ".Target_org_datadog_jmxfetch_reporter_JsonReporter",
    };
  }

  public static class FindTargetClassesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) List<Class<?>> result) {
      result.add(Target_datadog_jctools_counters_FixedSizeStripedLongCounterFields.class);
      result.add(Target_datadog_jctools_util_UnsafeRefArrayAccess.class);
      result.add(Target_org_datadog_jmxfetch_App.class);
      result.add(Target_org_datadog_jmxfetch_Status.class);
      result.add(Target_org_datadog_jmxfetch_reporter_JsonReporter.class);
    }
  }
}

package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import net.bytebuddy.asm.Advice;

public final class AnnotationSubstitutionProcessorInstrumentation
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
        "datadog.trace.instrumentation.graal.nativeimage.DeleteFieldAdvice");
    transformer.applyAdvice(
        isMethod().and(named("findTargetClasses")),
        AnnotationSubstitutionProcessorInstrumentation.class.getName()
            + "$FindTargetClassesAdvice");
  }

  public static class FindTargetClassesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) List<Class<?>> result) {
      result.add(Target_com_datadog_profiling_agent_ProcessContext.class);
      result.add(Target_datadog_jctools_util_UnsafeRefArrayAccess.class);

      // Only register JMXFetch substitutions if JMXFetch is actually present on the classpath.
      // We must load these classes reflectively (not using .class literals) to prevent
      // them from being discovered by GraalVM's annotation processor when JMXFetch is not present.
      if (isJmxFetchPresent()) {
        try {
          ClassLoader cl = FindTargetClassesAdvice.class.getClassLoader();
          result.add(
              Class.forName(
                  "datadog.trace.instrumentation.graal.nativeimage.Target_org_datadog_jmxfetch_App",
                  false,
                  cl));
          result.add(
              Class.forName(
                  "datadog.trace.instrumentation.graal.nativeimage.Target_org_datadog_jmxfetch_Status",
                  false,
                  cl));
          result.add(
              Class.forName(
                  "datadog.trace.instrumentation.graal.nativeimage.Target_org_datadog_jmxfetch_reporter_JsonReporter",
                  false,
                  cl));
        } catch (ClassNotFoundException e) {
          // Substitution classes not available, skip them
        }
      }
    }

    private static boolean isJmxFetchPresent() {
      try {
        Class.forName(
            "org.datadog.jmxfetch.App", false, FindTargetClassesAdvice.class.getClassLoader());
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }
  }
}

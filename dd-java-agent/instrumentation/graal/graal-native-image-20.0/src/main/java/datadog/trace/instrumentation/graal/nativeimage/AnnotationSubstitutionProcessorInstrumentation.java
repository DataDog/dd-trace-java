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
      //
      // NOTE: It's unclear why these substitutions get triggered during native-image compilation
      // when JMXFetch classes are not available. In theory, either:
      // 1) The substitutions should not be triggered at all (JMXFetch not in use), OR
      // 2) JMXFetch classes should be available (if JMXFetch is in use)
      //
      // However, in practice, adding profiling-scrubber to the agent triggers native-image to
      // discover these substitutions even when building applications that don't use JMXFetch,
      // causing "Substitution target not loaded" errors.
      //
      // This runtime check works around the issue for GraalVM 20.0 (which lacks the `onlyWith`
      // field in @TargetClass). For GraalVM 21+, the proper fix would be adding:
      //   @TargetClass(className = "org.datadog.jmxfetch.App", onlyWith = JmxFetchPresent.class)
      if (isJmxFetchPresent()) {
        result.add(Target_org_datadog_jmxfetch_App.class);
        result.add(Target_org_datadog_jmxfetch_Status.class);
        result.add(Target_org_datadog_jmxfetch_reporter_JsonReporter.class);
      }
    }

    private static boolean isJmxFetchPresent() {
      try {
        Class.forName("org.datadog.jmxfetch.App", false, FindTargetClassesAdvice.class.getClassLoader());
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }
  }
}

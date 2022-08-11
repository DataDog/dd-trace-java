package datadog.trace.instrumentation.systemexit;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class SystemExitInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public SystemExitInstrumentation() {
    super("systemexit");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "java.lang.System";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(isStatic())
            .and(named("exit"))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class)),
        getClass().getName() + "$SystemExitAdvice");
  }

  public static class SystemExitAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter() {
      try {
        Class.forName("datadog.trace.bootstrap.Agent")
            .getMethod("shutdown", Boolean.TYPE)
            .invoke(null, true);
      } catch (Throwable t) {
        System.err.printf("Failed to shutdown Agent: %s%n", t);
      }
      System.err.printf("SystemExitAdvice.methodEnter%n");
    }

    public static void muzzleCheck() {
    }
  }
}

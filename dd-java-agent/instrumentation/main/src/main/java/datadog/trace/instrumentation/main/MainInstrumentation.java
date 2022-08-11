package datadog.trace.instrumentation.main;

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
public class MainInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  private static final Logger log = LoggerFactory.getLogger(MainInstrumentation.class);

  public MainInstrumentation() {
    super("main");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("java.lang.Object"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(isStatic())
            .and(named("main"))
            .and(takesArguments(1))
            .and(takesArgument(0, String[].class)),
        getClass().getName() + "$MainAdvice");
  }

  public static class MainAdvice {
    @Advice.OnMethodEnter()
    public static void methodEnter() {
      System.err.println("MainAdvice.methodEnter");
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit() {
      System.err.println("MainAdvice.methodExit");
      try {
        Class.forName("datadog.trace.bootstrap.Agent")
            .getMethod("shutdown", Boolean.TYPE)
            .invoke(null, true);
      } catch (Throwable t) {
        log.debug("Failed to shutdown Agent", t);
      }
    }
  }
}

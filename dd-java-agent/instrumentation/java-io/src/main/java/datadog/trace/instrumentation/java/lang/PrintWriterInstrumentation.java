package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.XssModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class PrintWriterInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  private static final String PRINT_WRITER_CLASS = "java.io.PrintWriter";

  public PrintWriterInstrumentation() {
    super("printWriter");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return namedOneOf("org.apache.catalina.connector.CoyoteWriter")
        .and(not(named(PRINT_WRITER_CLASS)))
        .and(extendsClass(named(PRINT_WRITER_CLASS)));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("write"))
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, int.class)),
        PrintWriterInstrumentation.class.getName() + "$WriteStringAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("write"))
            .and(takesArguments(3))
            .and(takesArgument(0, char[].class))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, int.class)),
        PrintWriterInstrumentation.class.getName() + "$WriteCharArrayAdvice");
  }

  public static class WriteStringAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final String s) {
      final XssModule module = InstrumentationBridge.XSS;
      if (module != null) {
        module.onXss(s);
      }
    }
  }

  public static class WriteCharArrayAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final char[] array) {
      final XssModule module = InstrumentationBridge.XSS;
      if (module != null) {
        module.onXss(array);
      }
    }
  }
}

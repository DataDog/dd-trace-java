package datadog.trace.instrumentation.datanucleus;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.sql.Connection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class JDOConnectionInstrumentation extends Instrumenter.Default {
  public JDOConnectionInstrumentation() {
    super("datanucleus");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.datanucleus.api.jdo.JDOConnectionJDBCImpl");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        ElementMatchers.<MethodDescription>named("unwrap").and(takesArguments(1)),
        JDOConnectionInstrumentation.class.getName() + "$UnwrapAdvice");
  }

  public static class UnwrapAdvice {
    // Unwrapping is not properly implemented in datanucleus.  It returns "this" instead of the
    // delegate
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void fixUnwrap(
        @Advice.FieldValue("conn") final Connection delegate,
        @Advice.Argument(0) Class targetClass,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(readOnly = false) Object originalReturn) {

      if (throwable == null && Connection.class.equals(targetClass) && delegate != null) {
        originalReturn = delegate;
      }
    }
  }
}

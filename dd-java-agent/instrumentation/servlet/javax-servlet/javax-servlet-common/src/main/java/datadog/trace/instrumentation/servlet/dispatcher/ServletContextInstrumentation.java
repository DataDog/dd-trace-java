package datadog.trace.instrumentation.servlet.dispatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import javax.servlet.RequestDispatcher;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class ServletContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ServletContextInstrumentation() {
    super("servlet", "servlet-dispatcher");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.ServletContext";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.servlet.RequestDispatcher", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        returns(named("javax.servlet.RequestDispatcher"))
            .and(takesArgument(0, String.class))
            // javax.servlet.ServletContext.getRequestDispatcher
            // javax.servlet.ServletContext.getNamedDispatcher
            .and(isPublic()),
        ServletContextInstrumentation.class.getName() + "$RequestDispatcherTargetAdvice");
  }

  public static class RequestDispatcherTargetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void saveTarget(
        @Advice.Argument(0) final String target,
        @Advice.Return final RequestDispatcher dispatcher) {
      InstrumentationContext.get(RequestDispatcher.class, String.class).put(dispatcher, target);
    }
  }
}

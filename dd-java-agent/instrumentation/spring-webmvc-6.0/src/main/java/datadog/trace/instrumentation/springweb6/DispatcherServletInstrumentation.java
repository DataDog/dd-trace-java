package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class DispatcherServletInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public DispatcherServletInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.DispatcherServlet";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".HandlerMappingResourceNameFilter",
      packageName + ".PathMatchingHttpServletRequestWrapper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("onRefresh"))
            .and(takesArgument(0, named("org.springframework.context.ApplicationContext")))
            .and(takesArguments(1)),
        packageName + ".HandlerMappingAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("render"))
            .and(takesArgument(0, named("org.springframework.web.servlet.ModelAndView"))),
        packageName + ".RenderAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(nameStartsWith("processHandlerException"))
            .and(takesArgument(3, Exception.class)),
        packageName + ".ErrorHandlerAdvice");
  }
}

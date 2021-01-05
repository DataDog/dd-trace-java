package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class SpringAsyncInstrumentation extends Instrumenter.Tracing {

  public SpringAsyncInstrumentation() {
    super("spring-async");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.aop.interceptor.AsyncExecutionInterceptor");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpannedMethodInvocation", packageName + ".SpringSchedulingDecorator"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod()
            .and(
                named("invoke")
                    .and(
                        ElementMatchers.takesArgument(
                            0, named("org.aopalliance.intercept.MethodInvocation")))),
        packageName + ".SpringAsyncAdvice");
  }
}

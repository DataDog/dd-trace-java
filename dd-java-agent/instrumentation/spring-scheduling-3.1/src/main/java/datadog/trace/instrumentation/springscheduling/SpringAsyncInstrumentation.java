package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class SpringAsyncInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public SpringAsyncInstrumentation() {
    super("spring-async");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.aop.interceptor.AsyncExecutionInterceptor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpannedMethodInvocation", packageName + ".SpringSchedulingDecorator"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(
                named("invoke")
                    .and(takesArgument(0, named("org.aopalliance.intercept.MethodInvocation")))),
        packageName + ".SpringAsyncAdvice");
  }
}

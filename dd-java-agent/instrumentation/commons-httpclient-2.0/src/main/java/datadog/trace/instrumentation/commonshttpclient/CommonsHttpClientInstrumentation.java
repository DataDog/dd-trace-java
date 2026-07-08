package datadog.trace.instrumentation.commonshttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.apache.commons.httpclient.HttpMethod;

@AutoService(InstrumenterModule.class)
public class CommonsHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CommonsHttpClientInstrumentation() {
    super("commons-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.httpclient.HttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CommonsHttpClientDecorator",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".HelperMethods",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // All executeMethod overloads delegate to the 3-arg version:
    // executeMethod(HostConfiguration, HttpMethod, HttpState)
    // Instrumenting only the delegate avoids duplicate spans.
    transformer.applyAdvice(
        isMethod()
            .and(named("executeMethod"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.commons.httpclient.HostConfiguration")))
            .and(takesArgument(1, named("org.apache.commons.httpclient.HttpMethod")))
            .and(takesArgument(2, named("org.apache.commons.httpclient.HttpState"))),
        CommonsHttpClientInstrumentation.class.getName() + "$ExecuteMethodAdvice");
  }

  public static class ExecuteMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(1) final HttpMethod method) {
      return HelperMethods.doMethodEnter(method);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(1) final HttpMethod method,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, method, throwable);
    }
  }
}

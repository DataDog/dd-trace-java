package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/** @see org.springframework.http.HttpHeaders */
@AutoService(InstrumenterModule.class)
public class HttpHeadersInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public HttpHeadersInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.HttpHeaders";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("get")).and(takesArguments(Object.class)),
        packageName + ".TaintHttpHeadersGetAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getFirst")).and(takesArguments(String.class)),
        packageName + ".TaintHttpHeadersGetFirstAdvice");
    transformer.applyAdvice(
        isMethod().and(named("toSingleValueMap")).and(takesArguments(0)),
        packageName + ".TaintHttpHeadersToSingleValueMapAdvice");
    transformer.applyAdvice(
        isMethod().and(named("readOnlyHttpHeaders")).and(takesArguments(1)),
        packageName + ".TaintReadOnlyHttpHeadersAdvice");
  }
}

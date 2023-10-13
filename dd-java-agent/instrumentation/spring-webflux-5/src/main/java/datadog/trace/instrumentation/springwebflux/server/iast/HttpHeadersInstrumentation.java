package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/** @see org.springframework.http.HttpHeaders */
@AutoService(Instrumenter.class)
public class HttpHeadersInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public HttpHeadersInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.HttpHeaders";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("get")).and(takesArguments(Object.class)),
        packageName + ".TaintHttpHeadersGetAdvice");
    transformation.applyAdvice(
        isMethod().and(named("getFirst")).and(takesArguments(String.class)),
        packageName + ".TaintHttpHeadersGetFirstAdvice");
    transformation.applyAdvice(
        isMethod().and(named("toSingleValueMap")).and(takesArguments(0)),
        packageName + ".TaintHttpHeadersToSingleValueMapAdvice");
  }
}

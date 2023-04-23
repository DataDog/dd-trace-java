package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class ReadOnlyHttpHeadersInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public ReadOnlyHttpHeadersInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String muzzleDirective() {
    return "read_only_headers";
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.ReadOnlyHttpHeaders";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("get")).and(takesArguments(Object.class)),
        packageName + ".TaintHttpHeadersGetAdvice");
  }
}

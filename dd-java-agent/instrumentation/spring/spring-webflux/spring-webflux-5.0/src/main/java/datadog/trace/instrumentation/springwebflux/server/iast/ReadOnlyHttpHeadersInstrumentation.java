package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class ReadOnlyHttpHeadersInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("get")).and(takesArguments(Object.class)),
        packageName + ".TaintHttpHeadersGetAdvice");
  }
}

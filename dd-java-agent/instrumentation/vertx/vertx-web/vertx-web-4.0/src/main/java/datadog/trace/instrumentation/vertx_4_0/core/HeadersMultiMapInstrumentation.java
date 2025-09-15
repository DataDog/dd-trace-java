package datadog.trace.instrumentation.vertx_4_0.core;

import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HeadersMultiMapInstrumentation extends MultiMapInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  protected ElementMatcher.Junction<MethodDescription> matcherForGetAdvice() {
    // get(String) delegates on get(CharSequence)
    return takesArguments(1).and(takesArgument(0, CharSequence.class));
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.headers.HeadersMultiMap";
  }
}

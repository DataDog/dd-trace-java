package datadog.trace.instrumentation.vertx_4_0.core;

import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HeadersAdaptorInstrumentation extends MultiMapInstrumentation
    implements Instrumenter.ForKnownTypes {

  @Override
  protected ElementMatcher.Junction<MethodDescription> matcherForGetAdvice() {
    return takesArguments(1);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.core.http.impl.headers.HeadersAdaptor",
      "io.vertx.core.http.impl.headers.Http2HeadersAdaptor"
    };
  }
}

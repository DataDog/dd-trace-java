package datadog.trace.instrumentation.axway;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class AxwayHTTPPluginInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public AxwayHTTPPluginInstrumentation() {
    super("axway-api");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.vordel.dwe.http.ServerTransaction", int.class.getName());
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.vordel.dwe.http.HTTPPlugin",
      "com.vordel.dwe.http.ServerTransaction",
      "com.vordel.circuit.net.State"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".StateAdvice",
      packageName + ".AxwayHTTPPluginDecorator",
      packageName + ".HTTPPluginAdvice",
      packageName + ".ServerTransactionAdvice",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("invokeDispose")), packageName + ".HTTPPluginAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("tryTransaction")), packageName + ".StateAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("sendResponse")),
        packageName + ".ServerTransactionAdvice");
  }
}

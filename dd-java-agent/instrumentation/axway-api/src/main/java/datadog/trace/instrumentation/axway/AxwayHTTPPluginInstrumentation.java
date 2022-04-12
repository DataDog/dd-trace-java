package datadog.trace.instrumentation.axway;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;

@AutoService(Instrumenter.class)
public final class AxwayHTTPPluginInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("invokeDispose")), packageName + ".HTTPPluginAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("tryTransaction")), packageName + ".StateAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("sendResponse")),
        packageName + ".ServerTransactionAdvice");
  }
}

package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jersey.JerseyTaintHelper.taintMultiValuedMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class UriRoutingContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public UriRoutingContextInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String baseName = UriRoutingContextInstrumentation.class.getName();
    transformer.applyAdvice(
        named("getPathParameters").and(isPublic().and(takesArguments(boolean.class))),
        baseName + "$GetPathParametersAdvice");
    transformer.applyAdvice(
        named("getQueryParameters").and(isPublic().and(takesArguments(boolean.class))),
        baseName + "$GetQueryParametersAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.routing.UriRoutingContext";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JerseyTaintHelper",
    };
  }

  public static class GetPathParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    public static void onExit(@Advice.Return Map<String, List<String>> pathParams) {
      if (pathParams == null || pathParams.isEmpty()) {
        return;
      }
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }
      final TaintedObjects to = IastContext.Provider.taintedObjects();
      if (prop.isTainted(to, pathParams)) {
        return;
      }
      prop.taintObject(to, pathParams, SourceTypes.REQUEST_PATH_PARAMETER);
      taintMultiValuedMap(to, prop, SourceTypes.REQUEST_PATH_PARAMETER, pathParams);
    }
  }

  public static class GetQueryParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(@Advice.Return Map<String, List<String>> queryParams) {
      if (queryParams == null || queryParams.isEmpty()) {
        return;
      }
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }
      final TaintedObjects to = IastContext.Provider.taintedObjects();
      if (prop.isTainted(to, queryParams)) {
        return;
      }
      prop.taintObject(to, queryParams, SourceTypes.REQUEST_PARAMETER_VALUE);
      taintMultiValuedMap(to, prop, SourceTypes.REQUEST_PARAMETER_VALUE, queryParams);
    }
  }
}

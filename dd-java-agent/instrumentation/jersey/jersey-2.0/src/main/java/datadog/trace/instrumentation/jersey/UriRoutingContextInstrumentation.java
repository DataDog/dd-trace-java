package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jersey.JerseyTaintHelper.taintMultiValuedMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class UriRoutingContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetPathParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    public static void onExit(
        @Advice.Return Map<String, List<String>> pathParams,
        @ActiveRequestContext RequestContext reqCtx) {
      if (pathParams == null || pathParams.isEmpty()) {
        return;
      }
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (prop.isTainted(ctx, pathParams)) {
        return;
      }
      prop.taintObject(ctx, pathParams, SourceTypes.REQUEST_PATH_PARAMETER);
      taintMultiValuedMap(ctx, prop, SourceTypes.REQUEST_PATH_PARAMETER, pathParams);
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetQueryParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Return Map<String, List<String>> queryParams,
        @ActiveRequestContext RequestContext reqCtx) {
      if (queryParams == null || queryParams.isEmpty()) {
        return;
      }
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null) {
        return;
      }
      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (prop.isTainted(ctx, queryParams)) {
        return;
      }
      prop.taintObject(ctx, queryParams, SourceTypes.REQUEST_PARAMETER_VALUE);
      taintMultiValuedMap(ctx, prop, SourceTypes.REQUEST_PARAMETER_VALUE, queryParams);
    }
  }
}

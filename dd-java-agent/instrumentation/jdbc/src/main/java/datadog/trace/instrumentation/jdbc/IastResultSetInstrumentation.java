package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.sql.ResultSet;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class IastResultSetInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public IastResultSetInstrumentation() {
    super("jdbc", "jdbc-resultset", "iast-resultset");
  }

  @Override
  public String hierarchyMarkerType() {
    return "java.sql.ResultSet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.sql.ResultSet"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("next").and(takesArguments(0))),
        IastResultSetInstrumentation.class.getName() + "$NextAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("getString").or(named("getNString")))
            .and(takesArguments(int.class).or(takesArguments(String.class))),
        IastResultSetInstrumentation.class.getName() + "$GetParameterAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.ResultSet", Integer.class.getName());
  }

  public static class NextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This final ResultSet resultSet) {
      ContextStore<ResultSet, Integer> contextStore =
          InstrumentationContext.get(ResultSet.class, Integer.class);
      if (contextStore.get(resultSet) != null) {
        contextStore.put(resultSet, contextStore.get(resultSet) + 1);
      } else {
        // first time
        contextStore.put(resultSet, 1);
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(ResultSet.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.SQL_TABLE)
    public static void onExit(
        @Advice.Argument(0) Object argument,
        @Advice.Return final String value,
        @Advice.This final ResultSet resultSet,
        @ActiveRequestContext RequestContext reqCtx) {
      if (CallDepthThreadLocalMap.decrementCallDepth(ResultSet.class) > 0) {
        return;
      }
      ContextStore<ResultSet, Integer> contextStore =
          InstrumentationContext.get(ResultSet.class, Integer.class);
      if (contextStore.get(resultSet) > Config.get().getIastDbRowsToTaint()) {
        return;
      }
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      if (argument instanceof String) {
        module.taintString(ctx, value, SourceTypes.SQL_TABLE, (String) argument);
      } else {
        module.taintString(ctx, value, SourceTypes.SQL_TABLE);
      }
    }
  }
}

package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableDbVisitor;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.TaintableDb;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class IastResultSetInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  public IastResultSetInstrumentation() {
    super("jdbc", "resultset");
  }

  //  @Override
  //  public String instrumentedType() {
  //    return "java.sql.ResultSet";
  //  }

  @Override
  public String hierarchyMarkerType() {
    return "java.sql.ResultSet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(NameMatchers.named("java.sql.ResultSet"));
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableDbVisitor(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getInt")).and(takesArguments(int.class)),
        IastResultSetInstrumentation.class.getName() + "$GetParameterAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetParameterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.SQL_TABLE)
    public static void onExit(
        @Advice.Argument(0) final int columnIndex,
        @Advice.Return final Object value,
        @Advice.This final TaintableDb resultSet,
        @ActiveRequestContext RequestContext reqCtx) {
      //      int recordsRead = resultSet.$$DD$RecordsRead;
      //      int recordsRead = resultSet.$$DD$getRecordsRead();
      //      resultSet.$$DD$setRecordsRead(recordsRead + 1);
      //      if (recordsRead > 1) {
      //        return;
      //      }
      if (value == null) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      module.taintString(ctx, String.valueOf(value), SourceTypes.SQL_TABLE);
    }
  }
}

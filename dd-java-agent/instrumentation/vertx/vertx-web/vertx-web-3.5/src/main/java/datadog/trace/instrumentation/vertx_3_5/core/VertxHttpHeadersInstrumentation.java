package datadog.trace.instrumentation.vertx_3_5.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class VertxHttpHeadersInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  public static final Reference VERTX_HTTP_HEADERS =
      new Reference.Builder("io.vertx.core.http.impl.headers.VertxHttpHeaders").build();

  private final String className = VertxHttpHeadersInstrumentation.class.getName();

  public VertxHttpHeadersInstrumentation() {
    super("vertx", "vertx-3.5");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VERTX_HTTP_HEADERS};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.impl.headers.VertxHttpHeaders";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("get"))
            .and(takesArguments(1).and(takesArgument(0, CharSequence.class))),
        className + "$GetAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("getAll"))
            .and(takesArguments(1).and(takesArgument(0, CharSequence.class))),
        className + "$GetAllAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("entries")).and(takesArguments(0)),
        className + "$EntriesAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("names")).and(takesArguments(0)),
        className + "$NamesAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterGet(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final String result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        propagation.taintStringIfTainted(ctx, result, self, SourceTypes.REQUEST_HEADER_VALUE, name);
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class GetAllAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterGetAll(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final Collection<String> result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        if (propagation.isTainted(ctx, self)) {
          for (final String value : result) {
            propagation.taintString(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, name);
          }
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class EntriesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterEntries(
        @Advice.This final Object self,
        @Advice.Return final List<Map.Entry<String, String>> result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        if (propagation.isTainted(ctx, self)) {
          final Set<String> names = new HashSet<>();
          for (final Map.Entry<String, String> entry : result) {
            final String name = entry.getKey();
            final String value = entry.getValue();
            if (names.add(name)) {
              propagation.taintString(ctx, name, SourceTypes.REQUEST_HEADER_NAME, name);
            }
            propagation.taintString(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, name);
          }
        }
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  public static class NamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_NAME)
    public static void afterNames(
        @Advice.This final Object self,
        @Advice.Return final Set<String> result,
        @ActiveRequestContext RequestContext reqCtx) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        if (propagation.isTainted(ctx, self)) {
          for (final String name : result) {
            propagation.taintString(ctx, name, SourceTypes.REQUEST_HEADER_NAME, name);
          }
        }
      }
    }
  }
}

package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
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

@AutoService(Instrumenter.class)
public class HeadersAdaptorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes {

  private final String className = HeadersAdaptorInstrumentation.class.getName();

  public HeadersAdaptorInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.core.http.impl.HeadersAdaptor", "io.vertx.core.http.impl.Http2HeadersAdaptor"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("get")).and(takesArguments(1)),
        className + "$GetAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("getAll")).and(takesArguments(1)),
        className + "$GetAllAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("entries")).and(takesArguments(0)),
        className + "$EntriesAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("names")).and(takesArguments(0)),
        className + "$NamesAdvice");
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(knownMatchingTypes()));
  }

  public static class GetAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterGet(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final String result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        try {
          propagation.taintIfTainted(result, self, SourceTypes.REQUEST_HEADER_VALUE, name);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("get threw", e);
        }
      }
    }
  }

  public static class GetAllAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterGetAll(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final Collection<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        try {
          if (propagation.isTainted(self)) {
            final IastContext ctx = IastContext.Provider.get();
            final String headerName = name == null ? null : name.toString();
            for (final String value : result) {
              propagation.taint(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, headerName);
            }
          }
        } catch (final Throwable e) {
          propagation.onUnexpectedException("getAll threw", e);
        }
      }
    }
  }

  public static class EntriesAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void afterEntries(
        @Advice.This final Object self,
        @Advice.Return final List<Map.Entry<String, String>> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        try {
          if (propagation.isTainted(self)) {
            final IastContext ctx = IastContext.Provider.get();
            final Set<String> names = new HashSet<>();
            for (Map.Entry<String, String> entry : result) {
              final String name = entry.getKey();
              final String value = entry.getValue();
              if (names.add(name)) {
                propagation.taint(ctx, name, SourceTypes.REQUEST_HEADER_NAME, name);
              }
              propagation.taint(ctx, value, SourceTypes.REQUEST_HEADER_VALUE, name);
            }
          }
        } catch (final Throwable e) {
          propagation.onUnexpectedException("entries threw", e);
        }
      }
    }
  }

  public static class NamesAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_HEADER_NAME)
    public static void afterNames(
        @Advice.This final Object self, @Advice.Return final Set<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null && result != null && !result.isEmpty()) {
        try {
          if (propagation.isTainted(self)) {
            final IastContext ctx = IastContext.Provider.get();
            for (final String name : result) {
              propagation.taint(ctx, name, SourceTypes.REQUEST_HEADER_NAME, name);
            }
          }
        } catch (final Throwable e) {
          propagation.onUnexpectedException("names threw", e);
        }
      }
    }
  }
}

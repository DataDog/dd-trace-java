package datadog.trace.instrumentation.vertx_3_5.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class VertxHttpHeadersInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("get"))
            .and(takesArguments(1).and(takesArgument(0, CharSequence.class))),
        className + "$GetAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("getAll"))
            .and(takesArguments(1).and(takesArgument(0, CharSequence.class))),
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
    return new VisitingTransformer(new TaintableVisitor(instrumentedType()));
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
          propagation.taintIfInputIsTainted(
              SourceTypes.REQUEST_HEADER_VALUE,
              name == null ? null : name.toString(),
              result,
              self);
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
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(
              SourceTypes.REQUEST_HEADER_VALUE,
              name == null ? null : name.toString(),
              result,
              self);
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
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(SourceTypes.REQUEST_HEADER_VALUE, result, self);
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
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(SourceTypes.REQUEST_HEADER_NAME, result, self);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("names threw", e);
        }
      }
    }
  }
}

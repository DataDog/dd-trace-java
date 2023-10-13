package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

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
public class CaseInsensitiveHeadersInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = CaseInsensitiveHeadersInstrumentation.class.getName();

  public CaseInsensitiveHeadersInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.CaseInsensitiveHeaders";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("get"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        className + "$GetAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("getAll"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        className + "$GetAllAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("entries")).and(takesNoArguments()),
        className + "$EntriesAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("names")).and(takesNoArguments()),
        className + "$NamesAdvice");
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(instrumentedType()));
  }

  public static class GetAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void afterGet(
        @Advice.This final Object self,
        @Advice.Argument(0) final String name,
        @Advice.Return final String result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(
              SourceTypes.REQUEST_PARAMETER_VALUE, name, result, self);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("get threw", e);
        }
      }
    }
  }

  public static class GetAllAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void afterGetAll(
        @Advice.This final Object self,
        @Advice.Argument(0) final String name,
        @Advice.Return final Collection<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(
              SourceTypes.REQUEST_PARAMETER_VALUE, name, result, self);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("getAll threw", e);
        }
      }
    }
  }

  public static class EntriesAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void afterEntries(
        @Advice.This final Object self,
        @Advice.Return final List<Map.Entry<String, String>> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_VALUE, result, self);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("entries threw", e);
        }
      }
    }
  }

  public static class NamesAdvice {
    @Advice.OnMethodExit
    @Source(SourceTypes.REQUEST_PARAMETER_NAME)
    public static void afterNames(
        @Advice.This final Object self, @Advice.Return final Set<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        try {
          propagation.taintIfInputIsTainted(SourceTypes.REQUEST_PARAMETER_NAME, result, self);
        } catch (final Throwable e) {
          propagation.onUnexpectedException("names threw", e);
        }
      }
    }
  }
}

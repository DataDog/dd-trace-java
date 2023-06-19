package datadog.trace.instrumentation.vertx_4_0.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.iast.SourceTypes.namedSource;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Taintable.Source;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class MultiMapInstrumentation extends Instrumenter.Iast {

  private final String className = MultiMapInstrumentation.class.getName();

  public MultiMapInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {HTTP_1X_SERVER_RESPONSE};
  }

  protected abstract ElementMatcher.Junction<MethodDescription> matcherForGetAdvice();

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("get")).and(matcherForGetAdvice()),
        className + "$GetAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("getAll")).and(matcherForGetAdvice()),
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
    final TaintableVisitor visitor;
    if (this instanceof Instrumenter.ForSingleType) {
      visitor = new TaintableVisitor(((Instrumenter.ForSingleType) this).instrumentedType());
    } else {
      visitor = new TaintableVisitor(((Instrumenter.ForKnownTypes) this).knownMatchingTypes());
    }
    return new VisitingTransformer(visitor);
  }

  public static class GetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterGet(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final String result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        final Source source = propagation.firstTaintedSource(self);
        if (source != null) {
          propagation.taintIfInputIsTainted(
              source.getOrigin(), name == null ? null : name.toString(), result, self);
        }
      }
    }
  }

  public static class GetAllAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterGetAll(
        @Advice.This final Object self,
        @Advice.Argument(0) final CharSequence name,
        @Advice.Return final Collection<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        final Source source = propagation.firstTaintedSource(self);
        if (source != null) {
          propagation.taintIfInputIsTainted(
              source.getOrigin(), name == null ? null : name.toString(), result, self);
        }
      }
    }
  }

  public static class EntriesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterEntries(
        @Advice.This final Object self,
        @Advice.Return final List<Map.Entry<String, String>> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        final Source source = propagation.firstTaintedSource(self);
        if (source != null) {
          propagation.taintIfInputIsTainted(source.getOrigin(), result, self);
        }
      }
    }
  }

  public static class NamesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterNames(
        @Advice.This final Object self, @Advice.Return final Set<String> result) {
      final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation != null) {
        final Source source = propagation.firstTaintedSource(self);
        if (source != null) {
          propagation.taintIfInputIsTainted(namedSource(source.getOrigin()), result, self);
        }
      }
    }
  }
}

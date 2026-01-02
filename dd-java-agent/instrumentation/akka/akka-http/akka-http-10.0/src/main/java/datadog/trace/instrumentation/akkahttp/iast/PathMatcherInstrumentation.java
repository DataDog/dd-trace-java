package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/**
 * Taints request uri parameters by instrumenting the constructor of {@link
 * akka.http.scaladsl.server.PathMatcher.Matched}.
 */
public class PathMatcherInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.PathMatcher$Matched";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(3))
            .and(takesArgument(0, named("akka.http.scaladsl.model.Uri$Path")))
            .and(takesArgument(1, Object.class))
            .and(takesArgument(2, named("akka.http.scaladsl.server.util.Tuple"))),
        PathMatcherInstrumentation.class.getName() + "$PathMatcherAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  static class PathMatcherAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    static void onExit(
        @Advice.Argument(1) Object extractions, @ActiveRequestContext RequestContext reqCtx) {
      if (!(extractions instanceof scala.Tuple1)) {
        return;
      }

      scala.Tuple1 tuple = (scala.Tuple1) extractions;
      Object value = tuple._1();

      PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null || !(value instanceof String)) {
        return;
      }
      final String stringValue = (String) value;

      final IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);

      // in the test, 4 instances of PathMatcher$Match are created, all with the same value
      if (module.isTainted(ctx, stringValue)) {
        return;
      }

      module.taintString(ctx, stringValue, SourceTypes.REQUEST_PATH_PARAMETER);
    }
  }
}

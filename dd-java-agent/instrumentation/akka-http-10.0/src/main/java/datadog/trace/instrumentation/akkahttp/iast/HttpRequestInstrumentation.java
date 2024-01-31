package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.HttpHeader;
import akka.http.scaladsl.model.HttpRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.asm.Advice;
import scala.collection.Iterator;
import scala.collection.immutable.Seq;

/**
 * Propagates taint from the {@link HttpRequest} to the headers and to the entity.
 *
 * @see MakeTaintableInstrumentation makes {@link HttpRequest} taintable
 */
@AutoService(Instrumenter.class)
public class HttpRequestInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public HttpRequestInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.model.HttpRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("headers"))
            .and(returns(named("scala.collection.immutable.Seq")))
            .and(takesArguments(0)),
        HttpRequestInstrumentation.class.getName() + "$RequestHeadersAdvice");

    transformer.applyAdvice(
        isMethod().and(isPublic()).and(not(isStatic())).and(named("entity")).and(takesArguments(0)),
        HttpRequestInstrumentation.class.getName() + "$EntityAdvice");
  }

  @SuppressFBWarnings("BC_IMPOSSIBLE_INSTANCEOF")
  static class RequestHeadersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    static void onExit(
        @Advice.This HttpRequest thiz, @Advice.Return(readOnly = false) Seq<HttpHeader> headers) {
      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null || headers == null || headers.isEmpty()) {
        return;
      }

      if (!propagation.isTainted(thiz)) {
        return;
      }

      final IastContext ctx = IastContext.Provider.get();
      Iterator<HttpHeader> iterator = headers.iterator();
      while (iterator.hasNext()) {
        HttpHeader h = iterator.next();
        if (propagation.isTainted(h)) {
          continue;
        }
        // unfortunately, the call to h.value() is instrumented, but
        // because the call to taint() only happens after, the call is a noop
        propagation.taint(ctx, h, SourceTypes.REQUEST_HEADER_VALUE, h.name(), h.value());
      }
    }
  }

  static class EntityAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void onExit(
        @Advice.This HttpRequest thiz,
        @Advice.Return(readOnly = false, typing = DYNAMIC) Object entity) {
      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null || entity == null) {
        return;
      }

      if (propagation.isTainted(entity)) {
        return;
      }

      propagation.taintIfTainted(entity, thiz);
    }
  }
}

package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
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
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pekko.http.scaladsl.model.HttpHeader;
import org.apache.pekko.http.scaladsl.model.HttpRequest;

/**
 * Propagates taint from {@link HttpHeader} to their values, when they're retrieved.
 *
 * @see MakeTaintableInstrumentation makes {@link HttpHeader} taintable
 * @see HttpRequestInstrumentation propagates taint from {@link HttpRequest} to the headers, when
 *     they're retrieved
 */
@AutoService(InstrumenterModule.class)
public class HttpHeaderSubclassesInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public HttpHeaderSubclassesInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.pekko.http.scaladsl.model.HttpHeader";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("org.apache.pekko.http.scaladsl.model.")
        .and(not(named(hierarchyMarkerType())))
        .and(extendsClass(named(hierarchyMarkerType())));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("value")).and(takesArguments(0)).and(returns(String.class)),
        HttpHeaderSubclassesInstrumentation.class.getName() + "$HttpHeaderSubclassesAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  static class HttpHeaderSubclassesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void onExit(
        @Advice.This HttpHeader h,
        @Advice.Return String retVal,
        @ActiveRequestContext RequestContext reqCtx) {

      PropagationModule propagation = InstrumentationBridge.PROPAGATION;
      if (propagation == null) {
        return;
      }
      IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
      propagation.taintStringIfTainted(ctx, retVal, h);
    }
  }
}

package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Type;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpMessageConverterInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {

  public HttpMessageConverterInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Only apply this spring-framework instrumentation when spring-webmvc is also deployed.
    return hasClassNamed(
        "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.http.converter.HttpMessageConverter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("read"))
            .and(takesArguments(2))
            .and(takesArgument(0, Class.class))
            .and(takesArgument(1, named("org.springframework.http.HttpInputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterReadAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("read"))
            .and(takesArguments(3))
            .and(takesArgument(0, Type.class))
            .and(takesArgument(1, Class.class))
            .and(takesArgument(2, named("org.springframework.http.HttpInputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterReadAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class HttpMessageConverterReadAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Return final Object obj,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (obj == null || t != null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(reqCtx, obj);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
        }
        t = new BlockingException("Blocked request (for HttpMessageConverter/read)");
      }
    }
  }
}

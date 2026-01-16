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
import datadog.trace.agent.tooling.InstrumenterModule;
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

@AutoService(InstrumenterModule.class)
public class HttpMessageConverterInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public HttpMessageConverterInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("read"))
            .and(takesArguments(2))
            .and(takesArgument(0, Class.class))
            .and(takesArgument(1, named("org.springframework.http.HttpInputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterReadAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("read"))
            .and(takesArguments(3))
            .and(takesArgument(0, Type.class))
            .and(takesArgument(1, Class.class))
            .and(takesArgument(2, named("org.springframework.http.HttpInputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterReadAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("write"))
            .and(takesArguments(3))
            .and(takesArgument(0, Object.class))
            .and(takesArgument(1, named("org.springframework.http.MediaType")))
            .and(takesArgument(2, named("org.springframework.http.HttpOutputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterWriteAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("write"))
            .and(takesArguments(4))
            .and(takesArgument(0, Object.class))
            .and(takesArgument(1, Type.class))
            .and(takesArgument(2, named("org.springframework.http.MediaType")))
            .and(takesArgument(3, named("org.springframework.http.HttpOutputMessage"))),
        HttpMessageConverterInstrumentation.class.getName() + "$HttpMessageConverterWriteAdvice");
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

      // CharSequence or byte[] cannot be treated as parsed body content, as they may lead to false
      // positives in the WAF rules.
      // TODO: These types (CharSequence, byte[]) are candidates to being deserialized before being
      // sent to the WAF once we implement that feature.
      // Possible types received by this method include: String, byte[], various DTOs/POJOs,
      // Collections (List, Map), Jackson JsonNode objects, XML objects, etc.
      // We may need to add more types to this block list in the future.
      if (obj instanceof CharSequence || obj instanceof byte[]) {
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
          brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
        }
        t = new BlockingException("Blocked request (for HttpMessageConverter/read)");
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class HttpMessageConverterWriteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.Argument(0) final Object obj, @ActiveRequestContext RequestContext reqCtx) {
      if (obj == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.responseBody());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, obj);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
        }
        throw new BlockingException("Blocked response (for HttpMessageConverter/write)");
      }
    }
  }
}

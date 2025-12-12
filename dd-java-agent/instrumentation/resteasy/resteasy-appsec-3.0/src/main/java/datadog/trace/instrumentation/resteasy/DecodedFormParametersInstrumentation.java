package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import javax.ws.rs.core.MultivaluedMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.pool.TypePool;

@AutoService(InstrumenterModule.class)
public class DecodedFormParametersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public DecodedFormParametersInstrumentation() {
    super("resteasy");
  }

  public static final String NETTY_HTTP_REQUEST_CLASS_NAME =
      "org.jboss.resteasy.plugins.server.netty.NettyHttpRequest";

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.jboss.resteasy.plugins.server.BaseHttpRequest",
      "org.jboss.resteasy.plugins.server.servlet.HttpServletInputMessage",
      NETTY_HTTP_REQUEST_CLASS_NAME
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getDecodedFormParameters").and(takesArguments(0)),
        DecodedFormParametersInstrumentation.class.getName() + "$GetDecodedFormParametersAdvice");
  }

  @Override
  public ReferenceProvider runtimeMuzzleReferences() {
    return new CustomReferenceProvider();
  }

  public static class CustomReferenceProvider implements ReferenceProvider {
    private static final Reference BASE_HTTP_REQUEST_DECODED_PARAMETERS =
        new Reference.Builder("org.jboss.resteasy.plugins.server.BaseHttpRequest")
            .withField(
                new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
            .build();

    private static final Reference HTTP_SERVLET_INPUT_MESSAGE_DECODED_PARAMETERS =
        new Reference.Builder("org.jboss.resteasy.plugins.server.servlet.HttpServletInputMessage")
            .withField(
                new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
            .build();

    private static final Reference NETTY_HTTP_REQUEST_DECODED_PARAMETERS =
        new Reference.Builder(NETTY_HTTP_REQUEST_CLASS_NAME)
            .withField(
                new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
            .build();

    @Override
    public Iterable<Reference> buildReferences(TypePool typePool) {
      List<Reference> references = new ArrayList<>();
      references.add(BASE_HTTP_REQUEST_DECODED_PARAMETERS);
      references.add(HTTP_SERVLET_INPUT_MESSAGE_DECODED_PARAMETERS);
      if (typePool.describe(NETTY_HTTP_REQUEST_CLASS_NAME).isResolved()) {
        references.add(NETTY_HTTP_REQUEST_DECODED_PARAMETERS);
      }
      return references;
    }
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetDecodedFormParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue("decodedFormParameters") final MultivaluedMap<String, String> map) {
      // only if the field is initially null do we run the after() advice
      // this is so that further calls to getDecodedFormParameters(), when the
      // data has already been processed and saved, do not make the data be resubmitted to the IG
      return map == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.FieldValue("decodedFormParameters") final MultivaluedMap<String, String> map,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t,
        @Advice.Enter boolean proceed) {
      if (!proceed || map == null || map.isEmpty() || t != null) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(requestContext, map);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          t = new BlockingException("Blocked request (for getDecodedFormParameters)");
          requestContext.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }
}

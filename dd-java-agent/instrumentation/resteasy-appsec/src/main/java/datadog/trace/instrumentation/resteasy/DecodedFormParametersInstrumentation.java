package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import javax.ws.rs.core.MultivaluedMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DecodedFormParametersInstrumentation extends Instrumenter.AppSec {

  public DecodedFormParametersInstrumentation() {
    super("resteasy");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.jboss.resteasy.plugins.server.BaseHttpRequest")
        .or(named("org.jboss.resteasy.plugins.server.servlet.HttpServletInputMessage"))
        .or(named("org.jboss.resteasy.plugins.server.netty.NettyHttpRequest"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getDecodedFormParameters").and(takesArguments(0)),
        DecodedFormParametersInstrumentation.class.getName() + "$GetDecodedFormParametersAdvice");
  }

  private static final ReferenceMatcher BASE_HTTP_REQUEST_DECODED_PARAMETERS_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("org.jboss.resteasy.plugins.server.BaseHttpRequest")
              .withField(
                  new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
              .build());

  private static final ReferenceMatcher HTTP_SERVLET_INPUT_MESSAGE_DECODED_PARAMETERS_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder("org.jboss.resteasy.plugins.server.servlet.HttpServletInputMessage")
              .withField(
                  new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
              .build());

  public static final String NETTY_HTTP_REQUEST_CLASS_NAME =
      "org.jboss.resteasy.plugins.server.netty.NettyHttpRequest";
  private static final ReferenceMatcher NETTY_HTTP_REQUEST_DECODED_PARAMETERS_MATCHER =
      new ReferenceMatcher(
          new Reference.Builder(NETTY_HTTP_REQUEST_CLASS_NAME)
              .withField(
                  new String[0], 0, "decodedFormParameters", "Ljavax/ws/rs/core/MultivaluedMap;")
              .build());

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    final IReferenceMatcher baseMatcher =
        new IReferenceMatcher.ConjunctionReferenceMatcher(
            new IReferenceMatcher.ConjunctionReferenceMatcher(
                origMatcher, BASE_HTTP_REQUEST_DECODED_PARAMETERS_MATCHER),
            HTTP_SERVLET_INPUT_MESSAGE_DECODED_PARAMETERS_MATCHER);
    final IReferenceMatcher matcherWithNetty =
        new IReferenceMatcher.ConjunctionReferenceMatcher(
            baseMatcher, NETTY_HTTP_REQUEST_DECODED_PARAMETERS_MATCHER);

    return new IReferenceMatcher() {
      @Override
      public boolean matches(ClassLoader loader) {
        if (hasNetty(loader)) {
          return matcherWithNetty.matches(loader);
        } else {
          return baseMatcher.matches(loader);
        }
      }

      @Override
      public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
        if (hasNetty(loader)) {
          return matcherWithNetty.getMismatchedReferenceSources(loader);
        } else {
          return baseMatcher.getMismatchedReferenceSources(loader);
        }
      }

      private boolean hasNetty(ClassLoader loader) {
        boolean hasNetty = false;
        try {
          loader.loadClass(NETTY_HTTP_REQUEST_CLASS_NAME);
          hasNetty = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
        }

        return hasNetty;
      }
    };
  }

  public static class GetDecodedFormParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue("decodedFormParameters") final MultivaluedMap<String, String> map) {
      if (map == null) {
        return true; // proceed
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.FieldValue("decodedFormParameters") final MultivaluedMap<String, String> map,
        @Advice.Enter boolean proceed) {
      if (!proceed || map == null || map.isEmpty()) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, map);
    }
  }
}

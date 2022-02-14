package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

@AutoService(Instrumenter.class)
public class JettyAddListenerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public JettyAddListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.client.HttpRequest";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.api.Request", AgentSpan.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CallbackWrapper"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("listener"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Request$RequestListener"))),
        JettyAddListenerInstrumentation.class.getName() + "$WrapRequestListener");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onSuccess"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Request$SuccessListener"))),
        JettyAddListenerInstrumentation.class.getName() + "$WrapRequestSuccessListener");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onFailure"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Request$FailureListener"))),
        JettyAddListenerInstrumentation.class.getName() + "$WrapRequestFailureListener");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onComplete"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Response$CompleteListener"))),
        JettyAddListenerInstrumentation.class.getName() + "$WrapResponseCompleteListener");
  }

  public static class WrapRequestListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.This Request request,
        @Advice.Argument(value = 0, readOnly = false) Request.RequestListener listener) {
      if (!(listener instanceof CallbackWrapper)) {
        listener =
            new CallbackWrapper(
                activeSpan(),
                InstrumentationContext.get(Request.class, AgentSpan.class).get(request),
                listener);
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }

  public static class WrapRequestFailureListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.This Request request,
        @Advice.Argument(value = 0, readOnly = false) Request.FailureListener listener) {
      if (!(listener instanceof CallbackWrapper)) {
        listener =
            new CallbackWrapper(
                activeSpan(),
                InstrumentationContext.get(Request.class, AgentSpan.class).get(request),
                listener);
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }

  public static class WrapRequestSuccessListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.This Request request,
        @Advice.Argument(value = 0, readOnly = false) Request.SuccessListener listener) {
      if (!(listener instanceof CallbackWrapper)) {
        listener =
            new CallbackWrapper(
                activeSpan(),
                InstrumentationContext.get(Request.class, AgentSpan.class).get(request),
                listener);
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }

  public static class WrapResponseCompleteListener {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(
        @Advice.This Request request,
        @Advice.Argument(value = 0, readOnly = false) Response.CompleteListener listener) {
      if (!(listener instanceof CallbackWrapper)) {
        listener =
            new CallbackWrapper(
                activeSpan(),
                InstrumentationContext.get(Request.class, AgentSpan.class).get(request),
                listener);
      }
    }

    private String muzzleCheck(Request request) {
      return request.getMethod(); // Before 9.1 returns an HttpMethod.
    }
  }
}

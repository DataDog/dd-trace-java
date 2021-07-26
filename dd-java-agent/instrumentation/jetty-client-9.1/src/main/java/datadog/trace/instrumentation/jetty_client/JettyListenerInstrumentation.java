package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

@AutoService(Instrumenter.class)
public class JettyListenerInstrumentation extends Instrumenter.Tracing {
  public JettyListenerInstrumentation() {
    super("jetty-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.eclipse.jetty.client.api.Request$RequestListener")
        .or(hasClassesNamed("org.eclipse.jetty.client.api.Response$ResponseListener"));
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(
        namedOneOf(
            "org.eclipse.jetty.client.api.Request$RequestListener",
            "org.eclipse.jetty.client.api.Response$ResponseListener"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.HttpRequest", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("on"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Request"))),
        getClass().getName() + "$ListenerWithRequest");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("on"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Response"))),
        getClass().getName() + "$ListenerWithResponse");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("on"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Result"))),
        getClass().getName() + "$ListenerWithResult");
  }

  public static final class ListenerWithRequest {
    @Advice.OnMethodEnter
    public static TraceScope before(@Advice.Argument(0) final Request request) {
      // context comes from JettyClientInstrumentation
      return startTaskScope(
          InstrumentationContext.get(HttpRequest.class, State.class), (HttpRequest) request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class ListenerWithResponse {

    private static Field requestField;
    private static Method requestMethod;

    static {
      try {
        requestMethod = Response.class.getMethod("getRequest");
      } catch (NoSuchMethodException | SecurityException e) {
        // skip exception
        requestMethod = null;
      }

      if (requestMethod != null) {
        try {
          requestField = HttpResponse.class.getDeclaredField("request");
          requestField.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
          // skip exception
          requestField = null;
        }
      } else {
        requestField = null;
      }
    }

    @Advice.OnMethodEnter
    public static TraceScope before(@Advice.Argument(0) final Response response) {
      // context comes from JettyClientInstrumentation
      Request request = getRequestFromResponse(response);
      if (request != null) {
        return startTaskScope(
            InstrumentationContext.get(HttpRequest.class, State.class), (HttpRequest) request);
      }
      return null;
    }

    public static Request getRequestFromResponse(final Response response) {
      try {
        if (requestMethod != null) {
          return (Request) requestMethod.invoke(response);
        }
        if (requestField != null && response instanceof HttpResponse) {
          return (Request) requestField.get(response);
        }
      } catch (Exception e) {
        // skip exception
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      if (scope != null) {
        endTaskScope(scope);
      }
    }
  }

  public static final class ListenerWithResult {
    @Advice.OnMethodEnter
    public static TraceScope before(@Advice.Argument(0) final Result result) {
      // context comes from JettyClientInstrumentation
      return startTaskScope(
          InstrumentationContext.get(HttpRequest.class, State.class),
          (HttpRequest) result.getRequest());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      endTaskScope(scope);
    }
  }
}

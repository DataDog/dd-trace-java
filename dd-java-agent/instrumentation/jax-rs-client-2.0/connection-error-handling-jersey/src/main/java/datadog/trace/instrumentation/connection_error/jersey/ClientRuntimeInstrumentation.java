package datadog.trace.instrumentation.connection_error.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static org.glassfish.jersey.client.WrappingResponseCallback.handleProcessingException;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.ProcessingException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.glassfish.jersey.client.ClientRequest;

@AutoService(Instrumenter.class)
public class ClientRuntimeInstrumentation extends Instrumenter.Tracing {
  public ClientRuntimeInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-client");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.glassfish.jersey.client.ClientRuntime");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"org.glassfish.jersey.client.WrappingResponseCallback"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(4);
    transformers.put(
        isMethod()
            .and(namedOneOf("submit", "createRunnableForAsyncProcessing"))
            .and(
                takesArgument(0, named("org.glassfish.jersey.client.ClientRequest"))
                    .and(takesArgument(1, named("org.glassfish.jersey.client.ResponseCallback")))),
        "org.glassfish.jersey.client.WrappingResponseCallbackAdvice");
    transformers.put(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.glassfish.jersey.client.ClientRequest"))),
        getClass().getName() + "$HandleError");
    return unmodifiableMap(transformers);
  }

  public static class HandleError {
    @Advice.OnMethodExit(onThrowable = ProcessingException.class)
    public static void handleError(
        @Advice.Argument(0) ClientRequest request, @Advice.Thrown ProcessingException error) {
      if (null != error) {
        handleProcessingException(request, error);
      }
    }
  }
}

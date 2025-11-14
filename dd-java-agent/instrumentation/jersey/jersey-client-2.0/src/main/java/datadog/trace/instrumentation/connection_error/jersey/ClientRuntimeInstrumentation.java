package datadog.trace.instrumentation.connection_error.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static org.glassfish.jersey.client.WrappingResponseCallback.handleProcessingException;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import javax.ws.rs.ProcessingException;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.client.ClientRequest;

@AutoService(InstrumenterModule.class)
public class ClientRuntimeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ClientRuntimeInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-client");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.client.ClientRuntime";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"org.glassfish.jersey.client.WrappingResponseCallback"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("submit", "createRunnableForAsyncProcessing"))
            .and(
                takesArgument(0, named("org.glassfish.jersey.client.ClientRequest"))
                    .and(takesArgument(1, named("org.glassfish.jersey.client.ResponseCallback")))),
        "org.glassfish.jersey.client.WrappingResponseCallbackAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.glassfish.jersey.client.ClientRequest"))),
        getClass().getName() + "$HandleError");
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

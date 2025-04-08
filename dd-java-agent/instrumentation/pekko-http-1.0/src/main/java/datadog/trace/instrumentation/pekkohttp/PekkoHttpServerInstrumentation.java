package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.NotUsed;
import org.apache.pekko.http.scaladsl.HttpExt;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.http.scaladsl.settings.ServerSettings;
import org.apache.pekko.stream.javadsl.BidiFlow;
import org.apache.pekko.stream.scaladsl.Flow;

/**
 * This instrumentation wraps the user supplied request handler {@code Flow} for the pekko-http
 * {@code bindAndHandle} method and adds spans to the incoming requests from the server flow part of
 * the machinery.
 *
 * <p>Context and background:
 *
 * <p>A Pekko stream is driven by a state machine where the {@code GraphStageLogic} that is
 * constructed in the {@code createLogic} method is signalling demand by pulling and propagating
 * elements by pushing. This logic part of the state machine is driven by an {@code Actor} only
 * executed by one thread at a time, and is hence thread safe.
 *
 * <p>The {@code GraphStage} described below is a blueprint, and there will be one instance of the
 * {@code GraphStageLogic} created per connection to the server, so all requests over the same HTTP
 * connection will be handled by a single instance, with further guarantees that the request and
 * response elements will match up according to this.
 * https://pekko.apache.org/docs/pekko-http/current/server-side/low-level-api.html#request-response-cycle
 *
 * <p>Inside a stream the elements are propagated by responding to demand, {@code onPull}, and
 * moving elements, {@code onPush}, between an {@code Inlet} and an {@code Outlet}. This means that
 * when the logic push the {@code HttpRequest} to the user code and return, we have not yet run any
 * of the user request handling code, so there is no straight call chain where we send in an {@code
 * HttpRequest} and get back an {@code HttpResponse}. Instead we need to keep track of the {@code
 * Span} and {@code Scope} corresponding to a {@code HttpRequest} / {@code HttpResponse} pair in a
 * queue on the side, and close the {@code Span} at the head of the queue when the corresponding
 * {@code HttpResponse} comes back. Furthermore, this also means that there is no place where we are
 * guaranteed to see the same scope on the top of the stack for the same thread, so we can easily
 * close it. Instead the {@code Scope} is deliberately leaked when {@code HttpRequest} is pushed to
 * the user code, and if the same scope is on the top if the stack when the {@code HttpResponse}
 * comes back, it is immediately closed. If on the other hand, the scope has escaped, it will be
 * closed by cleanup code in the message processing instrumentation for the {@code Actor} and its
 * {@code Mailbox}.
 */
@AutoService(InstrumenterModule.class)
public final class PekkoHttpServerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PekkoHttpServerInstrumentation() {
    super("pekko-http", "pekko-http-server");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.HttpExt";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogWrapperHelper",
      packageName + ".DatadogServerRequestResponseFlowWrapper",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$1",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$2",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$3",
      packageName + ".DatadogServerRequestResponseFlowWrapper$1$4",
      packageName + ".PekkoHttpServerHeaders",
      packageName + ".PekkoHttpServerDecorator",
      packageName + ".UriAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("bindAndHandle")
            .and(takesArgument(0, named("org.apache.pekko.stream.scaladsl.Flow"))),
        getClass().getName() + "$PekkoHttpBindAndHandleAdvice");
  }

  public static class PekkoHttpBindAndHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler,
        @Advice.Argument(value = 4, readOnly = false) ServerSettings settings) {
      if (CallDepthThreadLocalMap.incrementCallDepth(HttpExt.class) == 0) {
        final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> wrapper =
            BidiFlow.fromGraph(new DatadogServerRequestResponseFlowWrapper(settings));
        handler = wrapper.reversed().join(handler.asJava()).asScala();
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit() {
      CallDepthThreadLocalMap.decrementCallDepth(HttpExt.class);
    }
  }
}

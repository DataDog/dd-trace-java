package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.settings.ServerSettings;
import akka.stream.javadsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation wraps the user supplied request handler {@code Flow} for the akka-http
 * {@code bindAndHandle} method and adds spans to the incoming requests from the server flow part of
 * the machinery.
 *
 * <p>Context and background:
 *
 * <p>An Akka stream is driven by a state machine where the {@code GraphStageLogic} that is
 * constructed in the {@code createLogic} method is signalling demand by pulling and propagating
 * elements by pushing. This logic part of the state machine is driven by an {@code Actor} only
 * executed by one thread at a time, and is hence thread safe.
 *
 * <p>The {@code GraphStage} described below is a blueprint, and there will be one instance of the
 * {@code GraphStageLogic} created per connection to the server, so all requests over the same HTTP
 * connection will be handled by a single instance, with further guarantees that the request and
 * response elements will match up according to this.
 * https://doc.akka.io/docs/akka-http/current/server-side/low-level-api.html#request-response-cycle
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
public final class AkkaHttpServerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.HttpExt";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("bindAndHandle").and(takesArgument(0, named("akka.stream.scaladsl.Flow"))),
        getClass().getName() + "$AkkaHttpBindAndHandleAdvice");
  }

  public static class AkkaHttpBindAndHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler,
        @Advice.Argument(value = 4, readOnly = false) ServerSettings settings) {
      handler = handler.asJava().recover(RecoverFromBlockedExceptionPF.INSTANCE).asScala();
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> wrapper =
          BidiFlow.fromGraph(new DatadogServerRequestResponseFlowWrapper(settings));
      handler = wrapper.reversed().join(handler.asJava()).asScala();
    }
  }
}

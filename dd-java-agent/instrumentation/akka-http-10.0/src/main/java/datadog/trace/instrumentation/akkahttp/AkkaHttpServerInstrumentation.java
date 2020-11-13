package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.AKKA_REQUEST;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders.GETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.settings.ServerSettings;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.javadsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.context.TraceScope;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

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
@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaHttpServerInstrumentation extends Instrumenter.Default {
  public AkkaHttpServerInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.HttpExt");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      getClass().getName() + "$DatadogWrapperHelper",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper$1",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper$1$1",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper$1$2",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper$1$3",
      getClass().getName() + "$DatadogServerRequestResponseFlowWrapper$1$4",
      packageName + ".AkkaHttpServerHeaders",
      packageName + ".AkkaHttpServerDecorator",
      packageName + ".UriAdapter",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("bindAndHandle").and(takesArgument(0, named("akka.stream.scaladsl.Flow"))),
        getClass().getName() + "$AkkaHttpBindAndHandleAdvice");
  }

  public static class AkkaHttpBindAndHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler,
        @Advice.Argument(value = 4, readOnly = false) ServerSettings settings) {
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> wrapper =
          BidiFlow.fromGraph(new DatadogServerRequestResponseFlowWrapper(settings));
      handler = wrapper.reversed().join(handler.asJava()).asScala();
    }
  }

  public static class DatadogWrapperHelper {
    public static AgentScope createSpan(final HttpRequest request) {
      final AgentSpan.Context extractedContext = propagate().extract(request, GETTER);
      final AgentSpan span = startSpan(AKKA_REQUEST, extractedContext);
      span.setMeasured(true);

      DECORATE.afterStart(span);
      DECORATE.onConnection(span, request);
      DECORATE.onRequest(span, request);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      return scope;
    }

    public static void finishSpan(final AgentSpan span, final HttpResponse response) {
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);

      span.finish();
    }

    public static void finishSpan(final AgentSpan span, final Throwable t) {
      DECORATE.onError(span, t);
      span.setTag(Tags.HTTP_STATUS, 500);
      DECORATE.beforeFinish(span);

      span.finish();
    }
  }

  public static class DatadogServerRequestResponseFlowWrapper
      extends GraphStage<BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest>> {
    private final Inlet<HttpRequest> requestInlet = Inlet.create("Datadog.server.requestIn");
    private final Outlet<HttpRequest> requestOutlet = Outlet.create("Datadog.server.requestOut");
    private final Inlet<HttpResponse> responseInlet = Inlet.create("Datadog.server.responseIn");
    private final Outlet<HttpResponse> responseOutlet = Outlet.create("Datadog.server.responseOut");
    private final BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape =
        BidiShape.of(responseInlet, responseOutlet, requestInlet, requestOutlet);

    private final int pipeliningLimit;

    public DatadogServerRequestResponseFlowWrapper(final ServerSettings settings) {
      this.pipeliningLimit = settings.getPipeliningLimit();
    }

    @Override
    public BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape() {
      return shape;
    }

    @Override
    public Attributes initialAttributes() {
      return Attributes.name("DatadogServerRequestResponseFlowWrapper");
    }

    @Override
    public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
      return new GraphStageLogic(shape) {
        {
          // The is one instance of this logic per connection, and the request/response is
          // guaranteed to be in order according to the docs at
          // https://doc.akka.io/docs/akka-http/current/server-side/low-level-api.html#request-response-cycle
          // and there can never be more outstanding requests than the pipeliningLimit
          // that this connection was created with. This means that we can safely
          // close the span at the front of the queue when we receive the response
          // from the user code, since it will match up to the request for that span.
          final Queue<AgentScope> scopes = new ArrayBlockingQueue<>(pipeliningLimit);

          // This is where the request comes in from the server and TCP layer
          setHandler(
              requestInlet,
              new AbstractInHandler() {
                @Override
                public void onPush() throws Exception {
                  final HttpRequest request = grab(requestInlet);
                  final AgentScope scope = DatadogWrapperHelper.createSpan(request);
                  scopes.add(scope);
                  push(requestOutlet, request);
                  // Since we haven't instrumented the akka stream state machine, we can't rely
                  // on spans and scopes being propagated during the push and pull of the
                  // element. Instead we let the scope leak intentionally here and clean it
                  // up when the user response comes back, or in the actor message processing
                  // instrumentation that drives this state machine.
                }

                @Override
                public void onUpstreamFinish() throws Exception {
                  // We will not receive any more requests from the server and TCP layer so stop
                  // sending them
                  complete(requestOutlet);
                }

                @Override
                public void onUpstreamFailure(final Throwable ex) throws Exception, Exception {
                  // We will not receive any more requests from the server and TCP layer so stop
                  // sending them
                  fail(requestOutlet, ex);
                }
              });

          // This is where demand comes in from the user code
          setHandler(
              requestOutlet,
              new AbstractOutHandler() {
                @Override
                public void onPull() throws Exception {
                  pull(requestInlet);
                }

                @Override
                public void onDownstreamFinish() throws Exception {
                  // We can not send out any more requests to the user code so stop receiving them
                  cancel(requestInlet);
                }
              });

          // This is where the response comes back from the user code
          setHandler(
              responseInlet,
              new AbstractInHandler() {
                @Override
                public void onPush() throws Exception {
                  final HttpResponse response = grab(responseInlet);
                  final AgentScope scope = scopes.poll();
                  if (scope != null) {
                    DatadogWrapperHelper.finishSpan(scope.span(), response);
                    // Check if the active scope is still the scope from when the request came in,
                    // and close it. If it's not, then it will be cleaned up actor message
                    // processing instrumentation that drives this state machine
                    TraceScope activeScope = activeScope();
                    if (activeScope == scope) {
                      scope.close();
                    }
                  }
                  push(responseOutlet, response);
                }

                @Override
                public void onUpstreamFinish() throws Exception {
                  // We will not receive any more responses from the user code, so clean up any
                  // remaining spans
                  AgentScope scope = scopes.poll();
                  while (scope != null) {
                    scope.span().finish();
                    scope = scopes.poll();
                  }
                  completeStage();
                }

                @Override
                public void onUpstreamFailure(final Throwable ex) throws Exception {
                  AgentScope scope = scopes.poll();
                  if (scope != null) {
                    // Mark the span as failed
                    DatadogWrapperHelper.finishSpan(scope.span(), ex);
                  }
                  // We will not receive any more responses from the user code, so clean up any
                  // remaining spans
                  scope = scopes.poll();
                  while (scope != null) {
                    scope.span().finish();
                    scope = scopes.poll();
                  }
                  fail(responseOutlet, ex);
                }
              });

          // This is where demand comes in from the server and TCP layer
          setHandler(
              responseOutlet,
              new AbstractOutHandler() {
                @Override
                public void onPull() throws Exception {
                  pull(responseInlet);
                }

                @Override
                public void onDownstreamFinish() throws Exception {
                  // We can not send out any more responses to the server and TCP layer so stop
                  // receiving them
                  cancel(responseInlet);
                }
              });
        }
      };
    }
  }
}

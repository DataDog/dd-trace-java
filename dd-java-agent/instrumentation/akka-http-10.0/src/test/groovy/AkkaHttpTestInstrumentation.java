import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
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
import datadog.trace.agent.test.base.HttpServerTest;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    // By wrapping the handler in fuseServerFlow instead of bindAndHandle, this instrumentation will
    // be before the regular AkkaHttpServerInstrumentation
    return agentBuilder
        .type(named("akka.http.scaladsl.HttpExt"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .advice(
                    isMethod()
                        .and(named("fuseServerFlow"))
                        .and(takesArgument(1, named("akka.stream.scaladsl.Flow"))),
                    getClass().getName() + "$AkkaServerTestAdvice"));
  }

  public static class AkkaServerTestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(value = 1, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler) {
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> wrapper =
          BidiFlow.fromGraph(new AkkaServerTestFlowWrapper());
      handler = wrapper.reversed().join(handler.asJava()).asScala();
    }
  }

  // This instrumentation mirrors the behavior in the normal AkkaHttpServerInstrumentation
  public static class AkkaServerTestFlowWrapper
      extends GraphStage<BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest>> {
    private final Inlet<HttpRequest> requestInlet = Inlet.create("Datadog.testServe.requestIn");
    private final Outlet<HttpRequest> requestOutlet =
        Outlet.create("Datadog.testServer.requestOut");
    private final Inlet<HttpResponse> responseInlet = Inlet.create("Datadog.testServer.responseIn");
    private final Outlet<HttpResponse> responseOutlet =
        Outlet.create("Datadog.testServer.responseOut");
    private final BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape =
        BidiShape.of(responseInlet, responseOutlet, requestInlet, requestOutlet);

    @Override
    public BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape() {
      return shape;
    }

    @Override
    public Attributes initialAttributes() {
      return Attributes.name("AkkaServerTestFlowWrapper");
    }

    @Override
    public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
      return new GraphStageLogic(shape) {
        {
          final Queue<AgentSpan> spans = new LinkedBlockingQueue<>();

          // This is where the request comes in from the server and TCP layer
          setHandler(
              requestInlet,
              new AbstractInHandler() {
                @Override
                public void onPush() throws Exception {
                  final HttpRequest request = grab(requestInlet);
                  if (HttpServerTest.ENABLE_TEST_ADVICE.get()) {
                    final AgentSpan span =
                        startSpan("TEST_SPAN").setTag(DDTags.RESOURCE_NAME, "ServerEntry");
                    final AgentScope scope = activateSpan(span);
                    scope.setAsyncPropagation(true);
                    spans.add(span);
                  } else {
                    spans.add(noopSpan());
                  }
                  try {
                    push(requestOutlet, request);
                  } finally {
                    // Since we haven't instrumented the akka stream state machine and the state
                    // machine will only execute events in this flow before picking up the next
                    // message, we let the scope leak, and be cleaned up by actor message
                    // processing.
                  }
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

          // This is where the requests goes out to the user code
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

                  final AgentSpan span = spans.poll();
                  if (span != null) {
                    span.finish();
                  }

                  push(responseOutlet, response);
                }

                @Override
                public void onUpstreamFinish() throws Exception {
                  // We will not receive any more responses from the user code, so clean up any
                  // remaining spans
                  AgentSpan span = spans.poll();
                  while (span != null) {
                    span.finish();
                    span = spans.poll();
                  }
                  completeStage();
                }

                @Override
                public void onUpstreamFailure(final Throwable ex) throws Exception {
                  // We will not receive any more responses from the user code, so clean up any
                  // remaining spans
                  AgentSpan span = spans.poll();
                  while (span != null) {
                    span.finish();
                    span = spans.poll();
                  }
                  fail(responseOutlet, ex);
                }
              });

          // This is where the response goes back to the server and TCP layer
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

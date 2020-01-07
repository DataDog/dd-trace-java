import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.Shape;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.stage.AbstractInOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.HttpServerTestAdvice;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.api.AgentScope;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaHttpTestInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named("akka.http.impl.engine.server.HttpServerBluePrint$"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .advice(named("requestTimeoutSupport"), AkkaServerTestAdvice.class.getName()));
  }

  public static class AkkaServerTestAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.Return(readOnly = false)
            BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed>
                requestTimeoutSupportLayer) {
      final BidiFlowWrapper wrapper = new BidiFlowWrapper();
      final BidiFlow<HttpResponse, HttpResponse, HttpRequest, HttpRequest, NotUsed> testSpanFlow =
          akka.stream.javadsl.BidiFlow.fromGraph(wrapper).asScala();
      requestTimeoutSupportLayer = testSpanFlow.atop(requestTimeoutSupportLayer);
    }

    public static class BidiFlowWrapper
        extends GraphStage<BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest>> {
      private final Inlet<HttpRequest> requestInlet = Inlet.create("Datadog.test.requestIn");
      private final Outlet<HttpRequest> requestOutlet = Outlet.create("Datadog.test.requestOut");
      private final Inlet<HttpResponse> responseInlet = Inlet.create("Datadog.test.responseIn");
      private final Outlet<HttpResponse> responseOutlet = Outlet.create("Datadog.test.responseOut");
      private final BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape =
          new BidiShape<>(responseInlet, responseOutlet, requestInlet, requestOutlet);

      @Override
      public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
        return new BidiFlowWrapperLogic(
            shape, requestInlet, requestOutlet, responseInlet, responseOutlet);
      }

      @Override
      public BidiShape<HttpResponse, HttpResponse, HttpRequest, HttpRequest> shape() {
        return shape;
      }
    }

    public static class BidiFlowWrapperLogic extends GraphStageLogic {
      final HttpRequestHandler httpRequestHandler;
      final HttpResponseHandler httpResponseHandler;
      final Queue<AgentScope> agentScopes = new LinkedBlockingQueue<>();

      public BidiFlowWrapperLogic(
          final Shape shape,
          final Inlet<HttpRequest> requestInlet,
          final Outlet<HttpRequest> requestOutlet,
          final Inlet<HttpResponse> responseInlet,
          final Outlet<HttpResponse> responseOutlet) {
        super(shape);

        httpRequestHandler = new HttpRequestHandler(this, requestInlet, requestOutlet);
        httpResponseHandler = new HttpResponseHandler(this, responseInlet, responseOutlet);

        setHandler(requestInlet, httpRequestHandler);
        setHandler(requestOutlet, httpRequestHandler);
        setHandler(responseInlet, httpResponseHandler);
        setHandler(responseOutlet, httpResponseHandler);
      }
    }

    public static class HttpRequestHandler extends AbstractInOutHandler {
      final BidiFlowWrapperLogic graph;
      final Inlet<HttpRequest> requestInlet;
      final Outlet<HttpRequest> requestOutlet;

      public HttpRequestHandler(
          final BidiFlowWrapperLogic graph,
          final Inlet<HttpRequest> requestInlet,
          final Outlet<HttpRequest> requestOutlet) {
        this.graph = graph;
        this.requestInlet = requestInlet;
        this.requestOutlet = requestOutlet;
      }

      @Override
      public void onPush() throws Exception {
        final HttpRequest request = graph.grab(requestInlet);

        final AgentScope scope = HttpServerTestAdvice.ServerEntryAdvice.methodEnter();
        if (scope != null) {
          graph.agentScopes.add(scope);
        }

        graph.push(requestOutlet, request);
      }

      @Override
      public void onUpstreamFailure(final Throwable ex) throws Exception {
        HttpServerTestAdvice.ServerEntryAdvice.methodExit(graph.agentScopes.poll());

        super.onUpstreamFailure(ex);
      }

      @Override
      public void onPull() throws Exception {
        graph.pull(requestInlet);
      }
    }

    public static class HttpResponseHandler extends AbstractInOutHandler {
      final BidiFlowWrapperLogic graph;
      final Inlet<HttpResponse> responseInlet;
      final Outlet<HttpResponse> responseOutlet;

      public HttpResponseHandler(
          final BidiFlowWrapperLogic graph,
          final Inlet<HttpResponse> responseInlet,
          final Outlet<HttpResponse> responseOutlet) {
        this.graph = graph;
        this.responseInlet = responseInlet;
        this.responseOutlet = responseOutlet;
      }

      @Override
      public void onPush() throws Exception {
        final HttpResponse response = graph.grab(responseInlet);

        HttpServerTestAdvice.ServerEntryAdvice.methodExit(graph.agentScopes.poll());

        graph.push(responseOutlet, response);
      }

      @Override
      public void onUpstreamFailure(final Throwable ex) throws Exception {
        HttpServerTestAdvice.ServerEntryAdvice.methodExit(graph.agentScopes.poll());

        super.onUpstreamFailure(ex);
      }

      @Override
      public void onPull() throws Exception {
        graph.pull(responseInlet);
      }

      @Override
      public void onDownstreamFinish() throws Exception, Exception {
        graph.cancel(responseInlet);
      }
    }
  }
}

package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerInstrumentation.DatadogWrapperHelper.createSpan;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerInstrumentation.DatadogWrapperHelper.finishSpan;
import static datadog.trace.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.api.AgentTracer.activeSpan;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.Shape;
import akka.stream.scaladsl.BidiFlow;
import akka.stream.scaladsl.Flow;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import datadog.trace.instrumentation.api.AgentSpan;

public class AkkaServerFlowWrapper {
  public static Flow<HttpRequest, HttpResponse, NotUsed> wrap(
      final Flow<HttpRequest, HttpResponse, NotUsed> handler) {
    return BidiFlow.fromGraph(createGraph()).join(handler);
  }

  public static GraphStage<BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse>>
      createGraph() {
    return new WrappedServerGraphStage();
  }

  public static class WrappedServerGraphStage
      extends GraphStage<BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse>> {
    private final Inlet<HttpRequest> requestInlet = Inlet.create("Datadog.requestIn");
    private final Outlet<HttpRequest> requestOutlet = Outlet.create("Datadog.requestOut");
    private final Inlet<HttpResponse> responseInlet = Inlet.create("Datadog.responseIn");
    private final Outlet<HttpResponse> responseOutlet = Outlet.create("Datadog.responseOut");
    private final BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse> shape =
        new BidiShape<>(requestInlet, requestOutlet, responseInlet, responseOutlet);

    @Override
    public GraphStageLogic createLogic(final Attributes inheritedAttributes) throws Exception {
      return new WrappedServerFlowLogic(
          shape, requestInlet, requestOutlet, responseInlet, responseOutlet);
    }

    @Override
    public BidiShape<HttpRequest, HttpRequest, HttpResponse, HttpResponse> shape() {
      return shape;
    }
  }

  public static class WrappedServerFlowLogic extends GraphStageLogic {
    public WrappedServerFlowLogic(
        final Shape shape,
        final Inlet<HttpRequest> requestInlet,
        final Outlet<HttpRequest> requestOutlet,
        final Inlet<HttpResponse> responseInlet,
        final Outlet<HttpResponse> responseOutlet) {
      super(shape);

      setHandler(requestInlet, new HttpRequestInHandler(this, requestInlet, requestOutlet));
      setHandler(requestOutlet, new HttpRequestOutHandler(this, requestInlet));
      setHandler(responseInlet, new HttpResponseInHandler(this, responseInlet, responseOutlet));
      setHandler(responseOutlet, new HttpResponseOutHandler(this, responseInlet));
    }
  }

  public static class HttpRequestInHandler extends AbstractInHandler {
    private final WrappedServerFlowLogic graph;
    private final Inlet<HttpRequest> requestInlet;
    private final Outlet<HttpRequest> requestOutlet;

    public HttpRequestInHandler(
        final WrappedServerFlowLogic graph,
        final Inlet<HttpRequest> requestInlet,
        final Outlet<HttpRequest> requestOutlet) {
      this.graph = graph;
      this.requestInlet = requestInlet;
      this.requestOutlet = requestOutlet;
    }

    @Override
    public void onPush() throws Exception {
      final HttpRequest request = graph.grab(requestInlet);
      createSpan(request);

      graph.push(requestOutlet, request);
    }

    @Override
    public void onUpstreamFailure(final Throwable ex) throws Exception, Exception {
      final AgentSpan span = activeSpan();
      if (span != null) {
        finishSpan(span, ex);
      }

      super.onUpstreamFailure(ex);
    }
  }

  public static class HttpRequestOutHandler extends AbstractOutHandler {
    private final WrappedServerFlowLogic graph;
    private final Inlet<HttpRequest> requestInlet;

    public HttpRequestOutHandler(
        final WrappedServerFlowLogic graph, final Inlet<HttpRequest> requestInlet) {
      this.graph = graph;
      this.requestInlet = requestInlet;
    }

    @Override
    public void onPull() throws Exception {
      graph.pull(requestInlet);
    }
  }

  public static class HttpResponseInHandler extends AbstractInHandler {
    private final WrappedServerFlowLogic graph;
    private final Inlet<HttpResponse> responseInlet;
    private final Outlet<HttpResponse> responseOutlet;

    public HttpResponseInHandler(
        final WrappedServerFlowLogic graph,
        final Inlet<HttpResponse> responseInlet,
        final Outlet<HttpResponse> responseOutlet) {
      this.graph = graph;
      this.responseInlet = responseInlet;
      this.responseOutlet = responseOutlet;
    }

    @Override
    public void onPush() throws Exception {
      final HttpResponse response = graph.grab(responseInlet);
      final AgentSpan span = activeSpan();
      finishSpan(span, response);
      activeScope().close();

      graph.push(responseOutlet, response);
    }

    @Override
    public void onUpstreamFailure(final Throwable ex) throws Exception {
      final AgentSpan span = activeSpan();
      finishSpan(span, ex);
      activeScope().close();

      super.onUpstreamFailure(ex);
    }
  }

  public static class HttpResponseOutHandler extends AbstractOutHandler {
    private final WrappedServerFlowLogic graph;
    private final Inlet<HttpResponse> responseInlet;

    public HttpResponseOutHandler(
        final WrappedServerFlowLogic graph, final Inlet<HttpResponse> responseInlet) {
      this.graph = graph;
      this.responseInlet = responseInlet;
    }

    @Override
    public void onPull() throws Exception {
      graph.pull(responseInlet);
    }
  }
}

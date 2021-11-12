package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.settings.ServerSettings;
import akka.stream.Attributes;
import akka.stream.BidiShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public class DatadogServerRequestResponseFlowWrapper
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
                  AgentScope activeScope = activeScope();
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

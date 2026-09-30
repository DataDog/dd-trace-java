package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.context.ContextScope;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import org.apache.pekko.http.scaladsl.settings.ServerSettings;
import org.apache.pekko.stream.Attributes;
import org.apache.pekko.stream.BidiShape;
import org.apache.pekko.stream.Inlet;
import org.apache.pekko.stream.Outlet;
import org.apache.pekko.stream.stage.AbstractInHandler;
import org.apache.pekko.stream.stage.AbstractOutHandler;
import org.apache.pekko.stream.stage.GraphStage;
import org.apache.pekko.stream.stage.GraphStageLogic;

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
        // https://pekko.apache.org/docs/pekko-http/current/server-side/low-level-api.html#request-response-cycle
        // and there can never be more outstanding requests than the pipeliningLimit
        // that this connection was created with. This means that we can safely
        // close the span at the front of the queue when we receive the response
        // from the user code, since it will match up to the request for that span.
        // Actor invocation cleanup owns the scopes; only contexts cross the response boundary.
        final Queue<Context> contexts = new ArrayBlockingQueue<>(pipeliningLimit);

        // This is where the request comes in from the server and TCP layer
        setHandler(
            requestInlet,
            new AbstractInHandler() {
              @Override
              public void onPush() throws Exception {
                final HttpRequest request = grab(requestInlet);
                final ContextScope scope = DatadogWrapperHelper.createSpanForFlow(request);
                contexts.add(scope.context());
                push(requestOutlet, request);
                // Legacy mode leaves the scope open so the surrounding actor can clean it up.
                // Context-manager mode swaps the context and the actor restores it on exit.
              }

              @Override
              public void onUpstreamFinish() throws Exception {
                // We will not receive any more requests from the server and TCP layer so stop
                // sending them
                complete(requestOutlet);
              }

              @Override
              public void onUpstreamFailure(final Throwable ex) throws Exception {
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
                final Context context = contexts.poll();
                if (context != null) {
                  DatadogWrapperHelper.finishSpan(context, response);
                }
                push(responseOutlet, response);
              }

              @Override
              public void onUpstreamFinish() throws Exception {
                // We will not receive any more responses from the user code, so clean up any
                // remaining spans
                Context context = contexts.poll();
                while (context != null) {
                  fromContext(context).finish();
                  context = contexts.poll();
                }
                completeStage();
              }

              @Override
              public void onUpstreamFailure(final Throwable ex) throws Exception {
                Context context = contexts.poll();
                if (context != null) {
                  // Mark the span as failed
                  DatadogWrapperHelper.finishSpan(context, ex);
                }
                // We will not receive any more responses from the user code, so clean up any
                // remaining spans
                context = contexts.poll();
                while (context != null) {
                  fromContext(context).finish();
                  context = contexts.poll();
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

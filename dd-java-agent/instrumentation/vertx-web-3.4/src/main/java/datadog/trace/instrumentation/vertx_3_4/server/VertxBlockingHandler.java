package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

public class VertxBlockingHandler implements Handler<RoutingContext> {
  public static final String REQUEST_BLOCKING_ACTION_KEY = "request_blocking_action";
  public static final Handler<RoutingContext> INSTANCE = new VertxBlockingHandler();

  private VertxBlockingHandler() {}

  @Override
  public void handle(RoutingContext ctx) {
    Flow.Action.RequestBlockingAction rba = ctx.get(REQUEST_BLOCKING_ACTION_KEY);

    HttpServerRequest request = ctx.request();
    HttpServerResponse response = ctx.response();

    response.setStatusCode(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
    String acceptHeader = request.getHeader("Accept");
    BlockingActionHelper.TemplateType type =
        BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
    byte[] template = BlockingActionHelper.getTemplate(type);
    response
        .headers()
        .add("Content-type", BlockingActionHelper.getContentType(type))
        .add("Content-length", Integer.toString(template.length));
    response.end(Buffer.buffer(template));
  }
}

package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.http.MutableHeaders;
import ratpack.http.Response;

public class BlockRequestHandler implements Handler {
  public static final Handler INSTANCE = new BlockRequestHandler();

  private BlockRequestHandler() {}

  @Override
  public void handle(Context ctx) {
    Flow.Action.RequestBlockingAction rba = ctx.get(Flow.Action.RequestBlockingAction.class);

    Response response = ctx.getResponse();
    response.status(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
    String acceptHeader = ctx.getRequest().getHeaders().get("Accept");
    BlockingActionHelper.TemplateType type =
        BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
    MutableHeaders headers = response.getHeaders();
    headers.set("Content-type", BlockingActionHelper.getContentType(type));
    byte[] template = BlockingActionHelper.getTemplate(type);
    headers.set("Content-length", Integer.toString(template.length));
    response.send(template);
  }
}

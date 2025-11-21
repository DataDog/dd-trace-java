package datadog.trace.instrumentation.jetty904;

import static datadog.trace.instrumentation.jetty904.JettyOnCommitBlockingHelper.CloseCallback.isInitialized;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyOnCommitBlockingHelper {

  private static final Logger log = LoggerFactory.getLogger(JettyOnCommitBlockingHelper.class);
  private static final ByteBuffer EMPTY_BB = ByteBuffer.allocate(0);

  public static boolean block(
      HttpChannel channel,
      HttpTransport transport,
      Flow.Action.RequestBlockingAction rba,
      Callback cb) {
    if (!isInitialized()) {
      return false;
    }

    Request request = channel.getRequest();
    Response response = channel.getResponse();
    request.setAttribute(HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);

    try {
      int statusCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());

      String acceptHeader = request.getHeader("Accept");
      HttpFields fields = new HttpFields();
      for (Map.Entry<String, String> e : rba.getExtraHeaders().entrySet()) {
        fields.put(e.getKey(), e.getValue());
      }

      BlockingContentType bct = rba.getBlockingContentType();
      HttpGenerator.ResponseInfo info;
      Callback closeCb = new CloseCallback(cb, channel);
      if (bct != BlockingContentType.NONE && !request.isHead()) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        fields.put("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        fields.put("Content-length", Integer.toString(template.length));

        info =
            new HttpGenerator.ResponseInfo(
                request.getHttpVersion(), fields, template.length, statusCode, null, false);

        // we need to update the upper layers too
        // so that the correct status code/headers get reported correctly on the span`
        response.reset();
        response.setStatus(statusCode);
        response.setContentType(BlockingActionHelper.getContentType(type));
        response.setContentLength(template.length);

        log.debug("Sending blocking response (non-empty body)");
        transport.send(info, ByteBuffer.wrap(template), true, closeCb);
      } else {
        info =
            new HttpGenerator.ResponseInfo(
                request.getHttpVersion(), fields, 0, statusCode, null, request.isHead());

        response.reset();
        response.setStatus(statusCode);

        transport.send(info, EMPTY_BB, true, closeCb);
      }
    } catch (Exception x) {
      log.warn("Error committing blocking response", x);
      return false;
    }
    return true;
  }

  public static final class CloseCallback implements org.eclipse.jetty.util.Callback {
    private static final Logger log = LoggerFactory.getLogger(CloseCallback.class);
    private final Callback delegate;
    private final HttpChannel channel;

    public CloseCallback(Callback delegate, HttpChannel channel) {
      this.delegate = delegate;
      this.channel = channel;
    }

    /**
     * @see org.eclipse.jetty.server.HttpChannel.CommitCallback
     */
    private void close() {
      closed(channel.getResponse().getHttpOutput());
      channel.getEndPoint().close();
    }

    private static final MethodHandle CLOSED;

    static {
      MethodHandle mh = null;
      try {
        Method closed = HttpOutput.class.getDeclaredMethod("closed");
        closed.setAccessible(true);
        mh = MethodHandles.lookup().unreflect(closed);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        log.warn(
            "Could not find HttpOutput#closed(). " + "Blocking for responses will not be available",
            e);
      }
      CLOSED = mh;
    }

    public static boolean isInitialized() {
      return CLOSED != null;
    }

    private void closed(HttpOutput httpOutput) {
      try {
        CLOSED.invoke(httpOutput);
      } catch (Throwable e) {
        log.debug("Error invoked closed()", e);
      }
    }

    @Override
    public void succeeded() {
      close();
      delegate.succeeded();
    }

    @Override
    public void failed(final Throwable x) {
      log.debug("Exception sending blocking response", x);
      close();
      delegate.failed(x);
    }
  }
}

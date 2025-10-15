package datadog.trace.instrumentation.jetty9421;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
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
      MetaData.Response info;
      Callback closeCb = new CloseCallback(cb, channel);
      if (bct != BlockingContentType.NONE && !request.isHead()) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        fields.put("Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        fields.put("Content-length", Integer.toString(template.length));

        info = new MetaData.Response(request.getHttpVersion(), statusCode, fields, template.length);
        if (!commit(channel, info)) {
          return false;
        }

        // we need to update the upper layers too
        // so that the correct status code/headers get reported correctly on the span`
        reset(channel);
        response.setStatus(statusCode);
        response.setContentType(BlockingActionHelper.getContentType(type));
        response.setContentLength(template.length);

        request.onResponseCommit();
        log.debug("Sending blocking response (non-empty body)");
        transport.send(info, false, ByteBuffer.wrap(template), true, closeCb);
      } else {
        info = new MetaData.Response(request.getHttpVersion(), statusCode, fields);

        reset(channel);
        response.setStatus(statusCode);

        request.onResponseCommit();
        transport.send(info, request.isHead(), EMPTY_BB, true, closeCb);
      }
    } catch (Exception x) {
      log.warn("Error committing blocking response", x);
      return false;
    }
    return true;
  }

  private static final MethodHandle COMMIT_METADATA;

  static {
    MethodHandle commitMh = null;
    try {
      Method commit = HttpChannel.class.getDeclaredMethod("commit", MetaData.Response.class);
      commit.setAccessible(true);
      commitMh = MethodHandles.lookup().unreflect(commit);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      log.warn(
          "Could not find method HttpChannel#commit(MetaData.Response). "
              + "Blocking on responses will be unavailable");
    }
    COMMIT_METADATA = commitMh;
  }

  public static boolean isInitialized() {
    return COMMIT_METADATA != null;
  }

  private static void reset(HttpChannel channel) {
    if (channel.getState().partialResponse()) {
      channel.getResponse().reset();
      channel.getState().commitResponse();
    } else {
      log.debug("Failed resetting response");
    }
  }

  private static boolean commit(HttpChannel channel, MetaData.Response info) {
    try {
      COMMIT_METADATA.invoke(channel, info);
    } catch (Throwable t) {
      log.warn("Failed calling commit(MetaData.Response) when writing blocking response", t);
      return false;
    }
    return true;
  }

  public static final class CloseCallback implements Callback {
    private static final Logger log = LoggerFactory.getLogger(CloseCallback.class);
    private final Callback delegate;
    private final HttpChannel channel;

    public CloseCallback(Callback delegate, HttpChannel channel) {
      this.delegate = delegate;
      this.channel = channel;
    }

    private void close() {
      closed(channel.getResponse().getHttpOutput());
      channel.getEndPoint().close();
    }

    private static final MethodHandle CLOSED;

    static {
      MethodHandle mh = null;
      try {
        mh =
            MethodHandles.lookup()
                .findVirtual(HttpOutput.class, "closed", MethodType.methodType(void.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        try {
          mh =
              MethodHandles.lookup()
                  .findVirtual(
                      HttpOutput.class,
                      "completed",
                      MethodType.methodType(void.class, Throwable.class));
          mh = MethodHandles.insertArguments(mh, 1, new Object[] {null});
        } catch (NoSuchMethodException | IllegalAccessException e2) {
          log.warn(
              "Can't find either closed() or completed() on HttpOutput. "
                  + "No blocking on responses will be possible",
              e2);
        }
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
        log.debug("Call to closed()/completed() failed", e);
      }
    }

    @Override
    public void succeeded() {
      close();
      delegate.succeeded();
      log.debug("State after blocking {}", channel.getState());
    }

    @Override
    public void failed(final Throwable x) {
      log.debug("Exception sending blocking response", x);
      close();
      delegate.failed(x);
      log.debug("State after blocking {}", channel.getState());
    }
  }
}

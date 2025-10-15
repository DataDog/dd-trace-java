package datadog.trace.instrumentation.jetty10;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpChannel;
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
      HttpFields fields = HttpFields.build();
      for (Map.Entry<String, String> e : rba.getExtraHeaders().entrySet()) {
        putHeader(fields, e.getKey(), e.getValue());
      }

      BlockingContentType bct = rba.getBlockingContentType();
      MetaData.Response info;
      Callback closeCb = new CloseCallback(cb, channel);
      if (bct != BlockingContentType.NONE && !request.isHead()) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        putHeader(fields, "Content-type", BlockingActionHelper.getContentType(type));
        byte[] template = BlockingActionHelper.getTemplate(type);
        putHeader(fields, "Content-length", Integer.toString(template.length));

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
        transport.send(request.getMetaData(), info, ByteBuffer.wrap(template), true, closeCb);
      } else {
        info = new MetaData.Response(request.getHttpVersion(), statusCode, fields);

        reset(channel);
        response.setStatus(statusCode);

        request.onResponseCommit();
        transport.send(request.getMetaData(), info, EMPTY_BB, true, closeCb);
      }
    } catch (Exception x) {
      log.warn("Error committing blocking response", x);
      return false;
    }
    return true;
  }

  private static final MethodHandle PUT_HEADER;

  static {
    MethodHandle mh = null;
    try {
      Class<?> mutableCls =
          Class.forName(
              "org.eclipse.jetty.http.HttpFields$Mutable",
              false,
              JettyOnCommitBlockingHelper.class.getClassLoader());
      Method put = mutableCls.getDeclaredMethod("put", String.class, String.class);
      put.setAccessible(true);
      mh = MethodHandles.lookup().unreflect(put);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
      log.warn(
          "Could not find method HttpFields$Mutable.put(String,String). "
              + "Blocking on responses will be unavailable");
    }
    PUT_HEADER = mh;
  }

  public static boolean isInitialized() {
    return PUT_HEADER != null;
  }

  private static void putHeader(HttpFields fields, String key, String value) {
    try {
      PUT_HEADER.invoke(fields, key, value);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
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
      Method commit = HttpChannel.class.getDeclaredMethod("commit", MetaData.Response.class);
      commit.setAccessible(true);
      commit.invoke(channel, info);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      log.warn("Failed calling commit(MetaData.Response) when writing blocking response", e);
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
      channel.getResponse().getHttpOutput().completed(null);
      channel.getEndPoint().close();
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

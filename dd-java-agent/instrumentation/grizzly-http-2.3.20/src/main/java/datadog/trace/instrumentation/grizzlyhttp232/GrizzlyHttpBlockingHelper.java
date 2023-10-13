package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RESPONSE_ATTRIBUTE;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Map;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.ProcessorResult;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.asyncqueue.PushBackHandler;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpServerFilter;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.memory.HeapBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrizzlyHttpBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(GrizzlyHttpBlockingHelper.class);

  /** @see HttpServerFilter#encodeHttpPacket(FilterChainContext, HttpPacket) */
  private static final MethodHandle ENCODE_HTTP_PACKET;

  private static final CompletionHandler CLOSE_COMPLETION_HANDLER = new CloseCompletionHandler();

  static {
    MethodHandle handle = null;
    Method encodeHttpPacket = null;

    try {
      encodeHttpPacket =
          HttpServerFilter.class.getDeclaredMethod(
              "encodeHttpPacket", FilterChainContext.class, HttpPacket.class);
      encodeHttpPacket.setAccessible(true);
    } catch (NoSuchMethodException nsme) {
      log.error(
          "Cannot find method HttpServerFilter::encodeHttpPacket. "
              + "Blocking will not be possible at the grizzly-http level");
    } catch (RuntimeException e) {
      log.error(
          "Exception trying to obtain handle for method HttpServerFilter::encodeHttpPacket. "
              + "Blocking will not be possible at the grizzly-http level",
          e);
    }

    if (encodeHttpPacket != null) {
      try {
        handle = MethodHandles.lookup().unreflect(encodeHttpPacket);
      } catch (IllegalAccessException e) {
        log.error(
            "Exception unreflecting method HttpServerFilter::encodeHttpPacket. "
                + "Blocking will not be possible at the grizzly-http level");
      }
    }

    ENCODE_HTTP_PACKET = handle;
  }

  private GrizzlyHttpBlockingHelper() {}

  public static NextAction block(
      FilterChainContext ctx,
      HttpServerFilter httpServerFilter,
      HttpRequestPacket httpRequest,
      HttpResponsePacket httpResponse,
      Flow.Action.RequestBlockingAction rba,
      NextAction nextAction) {
    if (ENCODE_HTTP_PACKET == null) {
      return nextAction;
    }

    HttpStatus status =
        HttpStatus.newHttpStatus(
            BlockingActionHelper.getHttpCode(rba.getStatusCode()), "Request Blocked");
    status.setValues(httpResponse);

    for (Map.Entry<String, String> h : rba.getExtraHeaders().entrySet()) {
      httpResponse.setHeader(h.getKey(), h.getValue());
    }

    HttpContent httpContent;
    if (rba.getBlockingContentType() != BlockingContentType.NONE) {
      String acceptHeader = httpRequest.getHeader("Accept");
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);

      httpResponse.setHeader("Content-type", BlockingActionHelper.getContentType(type));
      byte[] template = BlockingActionHelper.getTemplate(type);
      httpResponse.setContentLength(template.length);
      httpContent =
          HttpContent.builder(httpResponse).content(HeapBuffer.wrap(template)).last(true).build();
    } else {
      httpContent = HttpContent.builder(httpResponse).last(true).build();
    }

    Buffer buff;
    try {
      buff = (Buffer) ENCODE_HTTP_PACKET.invoke(httpServerFilter, ctx, httpContent);
    } catch (Throwable e) {
      log.error("Failure serializing http response for blocking", e);
      // in normal circumstances, recycle is called by encodeHttPacket
      httpContent.recycle();
      return nextAction;
    }

    ctx.write(buff);
    ctx.flush(CLOSE_COMPLETION_HANDLER);

    nextAction = ctx.getSuspendAction();
    // destroying the filter chain context should cause any further content to be ignored
    ctx.completeAndRecycle();

    return nextAction;
  }

  public static boolean block(
      FilterChainContext ctx,
      String acceptHeader,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      TraceSegment segment) {
    if (ENCODE_HTTP_PACKET == null) {
      return false;
    }
    int filterIdx = ctx.getFilterChain().indexOfType(HttpServerFilter.class);
    if (filterIdx == -1) {
      log.warn("Can't block: can't find filter of type HttpServerFilter");
      return false;
    }
    HttpServerFilter httpServerFilter = (HttpServerFilter) ctx.getFilterChain().get(filterIdx);

    HttpStatus status =
        HttpStatus.newHttpStatus(BlockingActionHelper.getHttpCode(statusCode), "Request Blocked");
    HttpResponsePacket httpResponse =
        (HttpResponsePacket) ctx.getAttributes().getAttribute(DD_RESPONSE_ATTRIBUTE);
    status.setValues(httpResponse);

    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
      httpResponse.setHeader(h.getKey(), h.getValue());
    }

    HttpContent httpContent;
    if (templateType != BlockingContentType.NONE) {
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(templateType, acceptHeader);

      httpResponse.setHeader("Content-type", BlockingActionHelper.getContentType(type));
      byte[] template = BlockingActionHelper.getTemplate(type);
      httpResponse.setContentLength(template.length);
      httpContent =
          HttpContent.builder(httpResponse).content(HeapBuffer.wrap(template)).last(true).build();
    } else {
      httpContent = HttpContent.builder(httpResponse).last(true).build();
    }

    Buffer buff;
    try {
      segment.effectivelyBlocked(); // last opportunity before HttpServerFilterAdvice runs
      buff = (Buffer) ENCODE_HTTP_PACKET.invoke(httpServerFilter, ctx, httpContent);
    } catch (Throwable e) {
      log.error("Failure serializing http response for blocking", e);
      // in normal circumstances, recycle is called by encodeHttPacket
      httpContent.recycle();
      return true;
    }

    ctx.write(buff);
    ctx.flush(CLOSE_COMPLETION_HANDLER);

    Context ictx = ctx.getInternalContext();
    // see ProcessorExecutor::execute
    ictx.setProcessor(JustCompleteProcessor.INSTANCE);

    return true;
  }

  public static class CloseCompletionHandler extends EmptyCompletionHandler {
    @Override
    public void completed(Object result) {
      final WriteResult<?, ?> wr = (WriteResult<?, ?>) result;
      try {
        // close tcp connection
        wr.getConnection().close().markForRecycle(false);
      } finally {
        wr.recycle();
      }
    }
  }

  public static class JustCompleteProcessor implements Processor {
    public static final Processor INSTANCE = new JustCompleteProcessor();

    @Override
    public Context obtainContext(Connection connection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessorResult process(Context context) {
      return ProcessorResult.createComplete();
    }

    @Override
    public void read(Connection connection, CompletionHandler completionHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void write(
        Connection connection,
        Object dstAddress,
        Object message,
        CompletionHandler completionHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void write(
        Connection connection,
        Object dstAddress,
        Object message,
        CompletionHandler completionHandler,
        MessageCloner messageCloner) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void write(
        Connection connection,
        Object dstAddress,
        Object message,
        CompletionHandler completionHandler,
        PushBackHandler pushBackHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInterested(IOEvent ioEvent) {
      return false;
    }

    @Override
    public void setInterested(IOEvent ioEvent, boolean isInterested) {
      throw new UnsupportedOperationException();
    }
  }
}

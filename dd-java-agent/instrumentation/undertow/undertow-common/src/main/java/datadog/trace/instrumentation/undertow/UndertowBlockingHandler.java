package datadog.trace.instrumentation.undertow;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DATADOG_UNDERTOW_CONTINUATION;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.channels.StreamSinkChannel;

public class UndertowBlockingHandler implements HttpHandler {
  public static final UndertowBlockingHandler INSTANCE = new UndertowBlockingHandler();
  private static final Logger log = LoggerFactory.getLogger(UndertowBlockingHandler.class);
  private static final ByteBuffer EMPTY_BB = ByteBuffer.allocate(0);

  public static final AttachmentKey<Flow.Action.RequestBlockingAction> REQUEST_BLOCKING_DATA =
      AttachmentKey.create(Flow.Action.RequestBlockingAction.class);
  public static final AttachmentKey<TraceSegment> TRACE_SEGMENT =
      AttachmentKey.create(TraceSegment.class);

  private UndertowBlockingHandler() {}

  @Override
  public void handleRequest(HttpServerExchange exchange) {
    Flow.Action.RequestBlockingAction rba = exchange.getAttachment(REQUEST_BLOCKING_DATA);
    commitBlockingResponse(exchange, rba);
  }

  private static void commitBlockingResponse(
      HttpServerExchange xchg, Flow.Action.RequestBlockingAction rba) {
    if (xchg.isResponseStarted()) {
      log.warn("response already committed, we can't change it");
      return;
    }

    TraceSegment segment = xchg.getAttachment(TRACE_SEGMENT);
    xchg.putAttachment(IgnoreSendAttribute.IGNORE_SEND_KEY, IgnoreSendAttribute.INSTANCE);

    try {
      xchg.setStatusCode(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
      HeaderMap headers = xchg.getResponseHeaders();
      headers.clear();
      HeaderValues acceptHeaderValues = xchg.getRequestHeaders().get(Headers.ACCEPT);

      for (Map.Entry<String, String> h : rba.getExtraHeaders().entrySet()) {
        headers.add(HttpString.tryFromString(h.getKey()), h.getValue());
      }

      final ByteBuffer buffer;
      if (rba.getBlockingContentType() != BlockingContentType.NONE) {
        String acceptHeader = acceptHeaderValues != null ? acceptHeaderValues.peekLast() : null;
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
        byte[] template = BlockingActionHelper.getTemplate(type, rba.getSecurityResponseId());

        headers.add(Headers.CONTENT_LENGTH, Integer.toString(template.length));
        headers.add(Headers.CONTENT_TYPE, BlockingActionHelper.getContentType(type));
        buffer = ByteBuffer.wrap(template);
      } else {
        buffer = EMPTY_BB;
      }

      segment.effectivelyBlocked();

      // blocking response to avoid having the intercepted caller and its callers interfere
      // even if this is an IO thread...
      // The async alternative at this level would be to set a write listener and call
      // resumeWrites()
      StreamSinkChannel responseChannel = xchg.getResponseChannel();
      long deadline = System.nanoTime() + 500 * 1000 * 1000; // 500 ms
      boolean finished = false;
      while (true) {
        if (buffer.hasRemaining()) {
          responseChannel.writeFinal(buffer);
        }
        if (buffer.hasRemaining() || !responseChannel.flush()) {
          long remaining = System.nanoTime() - deadline;
          if (remaining > 0) {
            try {
              responseChannel.awaitWritable(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedIOException iioe) {
              log.warn("Interrupted while waiting for write of blocking response");
              break;
            }
          } else {
            log.warn("Exceeded time for writing blocking response");
            break;
          }
        } else {
          finished = true;
          break;
        }
      }
      if (!finished) {
        log.warn("Did not manage to fully write blocking response");
      }

      markAsEffectivelyBlocked(xchg);
      xchg.endExchange();
    } catch (Throwable rte) {
      log.warn("Error sending blocking response", rte);
    }
  }

  private static void markAsEffectivelyBlocked(HttpServerExchange xchg) {
    AgentScope.Continuation continuation = xchg.getAttachment(DATADOG_UNDERTOW_CONTINUATION);
    if (continuation != null) {
      continuation.span().getRequestContext().getTraceSegment().effectivelyBlocked();
    }
  }
}

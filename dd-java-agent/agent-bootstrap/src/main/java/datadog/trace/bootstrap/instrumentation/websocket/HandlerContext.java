package datadog.trace.bootstrap.instrumentation.websocket;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HandlerContext {
  final Logger LOGGER = LoggerFactory.getLogger(HandlerContext.class);

  private final AgentSpan handshakeSpan;
  private AgentSpan websocketSpan;
  private final String sessionId;
  protected long msgChunks = 0;
  protected long msgSize = 0;
  private final CharSequence wsResourceName;
  protected long firstFrameTimestamp;

  public HandlerContext(AgentSpan handshakeSpan, String sessionId) {
    this.handshakeSpan = handshakeSpan;
    this.sessionId = sessionId;
    wsResourceName = ResourceNameExtractor.extractResourceName(handshakeSpan.getResourceName());
  }

  public AgentSpan getHandshakeSpan() {
    return handshakeSpan;
  }

  public AgentSpan getWebsocketSpan() {
    return websocketSpan;
  }

  public void setWebsocketSpan(AgentSpan websocketSpan) {
    this.websocketSpan = websocketSpan;
  }

  public String getSessionId() {
    return sessionId;
  }

  public long getMsgChunks() {
    return msgChunks;
  }

  public long getMsgSize() {
    return msgSize;
  }

  public CharSequence getWsResourceName() {
    return wsResourceName;
  }

  public long getFirstFrameTimestamp() {
    return firstFrameTimestamp;
  }

  public abstract CharSequence getMessageType();

  public void reset() {
    msgChunks = 0;
    websocketSpan = null;
    msgSize = 0;
    firstFrameTimestamp = 0;
  }

  public static class Receiver extends HandlerContext {
    private boolean msgSizeExtractorInitialized = false;
    private HandlersExtractor.SizeCalculator msgSizeCalculator;

    public Receiver(AgentSpan handshakeSpan, String sessionId) {
      super(handshakeSpan, sessionId);
    }

    @Override
    public CharSequence getMessageType() {
      return msgSizeCalculator != null ? msgSizeCalculator.getFormat() : null;
    }

    public void recordChunkData(Object data, boolean partialDelivery) {
      if (msgChunks++ == 0 && partialDelivery) {
        firstFrameTimestamp = SystemTimeSource.INSTANCE.getCurrentTimeNanos();
      }
      if (data == null) {
        return;
      }
      if (!msgSizeExtractorInitialized) {
        msgSizeExtractorInitialized = true;
        msgSizeCalculator = HandlersExtractor.getSizeCalculator(data);
      }

      if (msgSizeCalculator != null) {
        try {
          int sz = msgSizeCalculator.getLengthFunction().applyAsInt(data);
          msgSize += sz;
          if (partialDelivery && sz == 0) {
            msgChunks--; // if we receive an empty frame with the fin bit don't count it as a chunk
          }
        } catch (Throwable t) {
          LOGGER.debug(
              "Unable to calculate websocket message size for data type {}",
              data.getClass().getName(),
              t);
        }
      }
    }
  }

  public static class Sender extends HandlerContext {
    private CharSequence msgType;

    public Sender(AgentSpan handshakeSpan, String sessionId) {
      super(handshakeSpan, sessionId);
    }

    @Override
    public CharSequence getMessageType() {
      return msgType;
    }

    @Override
    public void reset() {
      super.reset();
      msgType = null;
    }

    public void recordChunkData(CharSequence type, int size) {
      msgChunks++;
      if (msgType == null) {
        msgType = type;
      }
      msgSize += size;
    }
  }
}

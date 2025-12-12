package datadog.trace.api.http;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Analogous to {@link StoredByteBody}, but Java doesn't support generics with scalar types. */
public class StoredCharBody implements StoredBodySupplier {

  private static final Logger LOGGER = LoggerFactory.getLogger(StoredCharBody.class);

  private static final int MIN_BUFFER_SIZE = 128; // chars
  private static final int MAX_BUFFER_SIZE = 128 * 1024; // 256k (char == 2 bytes)
  private static final int GROW_FACTOR = 4;
  private static final CharBuffer EMPTY_CHAR_BUFFER = CharBuffer.allocate(0);

  private final RequestContext httpContext;
  private final BiFunction<RequestContext, StoredBodySupplier, Void> startCb;
  private final BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb;
  private final StoredBodySupplier supplierInNotifications;

  private boolean listenerNotified;

  private char[] storedBody;
  private int storedBodyLen;
  private boolean bodyReadStarted = false;

  public StoredCharBody(
      RequestContext httpContext,
      BiFunction<RequestContext, StoredBodySupplier, Void> startCb,
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb,
      int lengthHint) {
    this(httpContext, startCb, endCb, lengthHint, null);
  }

  StoredCharBody(
      RequestContext httpContext,
      BiFunction<RequestContext, StoredBodySupplier, Void> startCb,
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb,
      int lengthHint,
      StoredBodySupplier supplierInNotifications) {
    this.httpContext = httpContext;
    this.startCb = startCb;
    this.endCb = endCb;

    if (lengthHint != 0) {
      int initialSize = Math.max(MIN_BUFFER_SIZE, Math.min(lengthHint, MAX_BUFFER_SIZE));
      this.storedBody = new char[initialSize];
    }

    this.supplierInNotifications = supplierInNotifications != null ? supplierInNotifications : this;
  }

  public synchronized void appendData(char[] chars, int start, int end) {
    try {
      int newDataLen = end - start;
      if (newDataLen <= 0) {
        return;
      }
      if (!maybeExtendStorage(newDataLen)) {
        return;
      }

      int lenToCopy = Math.min(newDataLen, capacityLeft());
      System.arraycopy(chars, start, this.storedBody, this.storedBodyLen, lenToCopy);

      this.storedBodyLen += lenToCopy;
    } catch (final Throwable e) {
      LOGGER.debug("Error appending char array chunk", e);
    }
    maybeNotifyStart();
  }

  public synchronized void appendData(CharBuffer buffer) {
    try {
      int inputLen = buffer.remaining();
      if (inputLen == 0) {
        return;
      }
      if (!maybeExtendStorage(inputLen)) {
        return;
      }
      int lenToCopy = Math.min(inputLen, capacityLeft());

      buffer.get(this.storedBody, this.storedBodyLen, lenToCopy);
      this.storedBodyLen += lenToCopy;
    } catch (final Throwable e) {
      LOGGER.debug("Error appending char buffer", e);
    }
    maybeNotifyStart();
  }

  private boolean maybeExtendStorage(int newDataLen) {
    if (this.storedBody == null) {
      int initialSize = Math.max(Math.min(newDataLen, MAX_BUFFER_SIZE), MIN_BUFFER_SIZE);
      this.storedBody = new char[initialSize];
    } else if (this.storedBodyLen == MAX_BUFFER_SIZE) {
      return false;
    } else if (capacityLeft() < newDataLen) {
      int newSize =
          Math.min(
              Math.max(this.storedBodyLen + newDataLen, this.storedBodyLen * GROW_FACTOR),
              MAX_BUFFER_SIZE);
      this.storedBody = Arrays.copyOf(this.storedBody, newSize);
    }
    return true;
  }

  public synchronized void appendData(String s) {
    try {
      int newDataLen = s.length();
      if (!maybeExtendStorage(newDataLen)) {
        return;
      }

      int lenToCopy = Math.min(newDataLen, capacityLeft());
      s.getChars(0, lenToCopy, this.storedBody, this.storedBodyLen);

      this.storedBodyLen += lenToCopy;

    } catch (final Throwable e) {
      LOGGER.debug("Error appending string", e);
    }
    maybeNotifyStart();
  }

  private int capacityLeft() {
    return this.storedBody.length - this.storedBodyLen;
  }

  /**
   * @param utf16CodeUnit an int in the range 0-0xFFFF
   */
  public synchronized void appendData(int utf16CodeUnit) {
    try {
      if (utf16CodeUnit < 0) {
        return;
      }
      if (!maybeExtendStorage(1)) {
        return;
      }
      this.storedBody[this.storedBodyLen] = (char) utf16CodeUnit;
      this.storedBodyLen += 1;

    } catch (final Throwable e) {
      LOGGER.debug("Error appending code unit", e);
    }
    maybeNotifyStart();
  }

  void maybeNotifyStart() {
    if (!bodyReadStarted) {
      bodyReadStarted = true;
      this.startCb.apply(httpContext, supplierInNotifications);
    }
  }

  public synchronized Flow<Void> maybeNotify() {
    if (!listenerNotified) {
      listenerNotified = true;
      if (!bodyReadStarted) {
        this.startCb.apply(httpContext, supplierInNotifications);
      }
      return this.endCb.apply(httpContext, supplierInNotifications);
    }
    return Flow.ResultFlow.empty();
  }

  public synchronized void maybeNotifyAndBlock() {
    Flow<Void> flow = maybeNotify();
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;

      BlockResponseFunction blockResponseFunction = httpContext.getBlockResponseFunction();
      if (blockResponseFunction != null) {
        blockResponseFunction.tryCommitBlockingResponse(httpContext.getTraceSegment(), rba);
      }
      throw new BlockingException("Blocked request (for request body stream read)");
    }
  }

  @Override
  public synchronized CharBuffer get() {
    if (this.storedBodyLen == 0) {
      return EMPTY_CHAR_BUFFER;
    }
    return CharBuffer.wrap(this.storedBody, 0, this.storedBodyLen);
  }

  synchronized boolean isLimitReached() {
    return this.storedBodyLen == MAX_BUFFER_SIZE;
  }

  // for StoredByteBody's reencodeAsLatin1
  synchronized void dropData() {
    this.storedBodyLen = 0;
  }
}

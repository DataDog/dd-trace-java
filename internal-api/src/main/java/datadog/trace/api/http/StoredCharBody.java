package datadog.trace.api.http;

import java.nio.CharBuffer;
import java.util.Arrays;

/** Analogous to {@link StoredByteBody}, but Java doesn't support generics with scalar types. */
@SuppressWarnings("Duplicates")
public class StoredCharBody implements StoredBodySupplier {
  private static final int MIN_BUFFER_SIZE = 128; // chars
  private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 2 MB (char == 2 bytes)
  private static final int GROW_FACTOR = 4;

  private boolean listenerNotified;
  private final StoredBodyListener listener;

  private char[] storedBody;
  private int storedBodyLen;
  private boolean bodyReadStarted = false;

  public StoredCharBody(StoredBodyListener listener) {
    this.listener = listener;
  }

  public synchronized void appendData(char[] chars, int start, int end) {
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
    int newDataLen = s.length();
    if (!maybeExtendStorage(newDataLen)) {
      return;
    }

    int lenToCopy = Math.min(newDataLen, capacityLeft());
    s.getChars(0, lenToCopy, this.storedBody, this.storedBodyLen);

    this.storedBodyLen += lenToCopy;

    maybeNotifyStart();
  }

  private int capacityLeft() {
    return this.storedBody.length - this.storedBodyLen;
  }

  public synchronized void appendData(int codeUnit) {
    if (codeUnit < 0) {
      return;
    }
    if (!maybeExtendStorage(1)) {
      return;
    }
    this.storedBody[this.storedBodyLen] = (char) codeUnit;
    this.storedBodyLen += 1;

    maybeNotifyStart();
  }

  private void maybeNotifyStart() {
    if (!bodyReadStarted) {
      bodyReadStarted = true;
      listener.onBodyStart(this);
    }
  }

  public synchronized void maybeNotify() {
    if (!listenerNotified) {
      listenerNotified = true;
      if (!bodyReadStarted) {
        listener.onBodyStart(this);
      }
      listener.onBodyEnd(this);
    }
  }

  @Override
  public synchronized CharSequence get() {
    if (this.storedBodyLen == 0) {
      return "";
    }
    return CharBuffer.wrap(this.storedBody, 0, this.storedBodyLen);
  }
}

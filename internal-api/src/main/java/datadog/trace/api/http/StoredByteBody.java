package datadog.trace.api.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** @see StoredCharBody */
@SuppressWarnings("Duplicates")
public class StoredByteBody implements StoredBodySupplier {
  private static final int MIN_BUFFER_SIZE = 128; // bytes
  private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1 MB
  private static final int GROW_FACTOR = 4;

  private static final Charset UTF_8 = StandardCharsets.UTF_8;
  private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

  private final StoredBodyListener listener;
  private boolean listenerNotified;

  private Charset charset;
  private byte[] storedBody;
  private int storedBodyLen;
  private boolean bodyReadStarted = false;

  public StoredByteBody(StoredBodyListener listener) {
    this.listener = listener;
  }

  public synchronized void appendData(byte[] bytes, int start, int end) {
    int newDataLen = end - start;
    if (!maybeExtendStorage(newDataLen)) {
      return;
    }

    int lenToCopy = Math.min(newDataLen, this.storedBody.length - this.storedBodyLen);
    System.arraycopy(bytes, start, this.storedBody, this.storedBodyLen, lenToCopy);

    this.storedBodyLen += lenToCopy;

    maybeNotifyStart();
  }

  private boolean maybeExtendStorage(int newDataLen) {
    if (this.storedBody == null) {
      int initialSize = Math.max(Math.min(newDataLen, MAX_BUFFER_SIZE), MIN_BUFFER_SIZE);
      this.storedBody = new byte[initialSize];
    } else if (this.storedBodyLen == MAX_BUFFER_SIZE) {
      return false;
    } else if (this.storedBody.length - this.storedBodyLen < newDataLen) {
      int newSize =
          Math.min(
              Math.max(this.storedBodyLen + newDataLen, this.storedBodyLen * GROW_FACTOR),
              MAX_BUFFER_SIZE);
      this.storedBody = Arrays.copyOf(this.storedBody, newSize);
    }
    return true;
  }

  public synchronized void appendData(int byteValue) {
    if (byteValue < 0 || byteValue > 255) {
      return;
    }
    if (!maybeExtendStorage(1)) {
      return;
    }
    this.storedBody[this.storedBodyLen] = (byte) byteValue;
    this.storedBodyLen += 1;

    maybeNotifyStart();
  }

  public synchronized void setCharset(Charset charset) {
    this.charset = charset;
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
        this.listener.onBodyStart(this);
      }
      this.listener.onBodyEnd(this);
    }
  }

  @Override
  public synchronized String get() {
    if (this.storedBodyLen == 0) {
      return "";
    }
    if (this.charset != null) {
      return new String(this.storedBody, 0, this.storedBodyLen, this.charset);
    }

    if (isWellFormed(this.storedBody, 0, this.storedBodyLen)) {
      return new String(this.storedBody, 0, this.storedBodyLen, UTF_8);
    }

    return new String(this.storedBody, 0, this.storedBodyLen, ISO_8859_1);
  }

  // copied from com.google.common.base.Utf8;
  private static boolean isWellFormed(byte[] bytes, int off, int len) {
    int end = off + len;
    // Look for the first non-ASCII character.
    for (int i = off; i < end; i++) {
      if (bytes[i] < 0) {
        return isWellFormedSlowPath(bytes, i, end);
      }
    }
    return true;
  }

  private static boolean isWellFormedSlowPath(byte[] bytes, int off, int end) {
    int index = off;
    while (true) {
      int byte1;

      // Optimize for interior runs of ASCII bytes.
      do {
        if (index >= end) {
          return true;
        }
      } while ((byte1 = bytes[index++]) >= 0);

      if (byte1 < (byte) 0xE0) {
        // Two-byte form.
        if (index == end) {
          return false;
        }
        // Simultaneously check for illegal trailing-byte in leading position
        // and overlong 2-byte form.
        if (byte1 < (byte) 0xC2 || bytes[index++] > (byte) 0xBF) {
          return false;
        }
      } else if (byte1 < (byte) 0xF0) {
        // Three-byte form.
        if (index + 1 >= end) {
          return false;
        }
        int byte2 = bytes[index++];
        if (byte2 > (byte) 0xBF
            // Overlong? 5 most significant bits must not all be zero.
            || (byte1 == (byte) 0xE0 && byte2 < (byte) 0xA0)
            // Check for illegal surrogate codepoints.
            || (byte1 == (byte) 0xED && (byte) 0xA0 <= byte2)
            // Third byte trailing-byte test.
            || bytes[index++] > (byte) 0xBF) {
          return false;
        }
      } else {
        // Four-byte form.
        if (index + 2 >= end) {
          return false;
        }
        int byte2 = bytes[index++];
        if (byte2 > (byte) 0xBF
            // Check that 1 <= plane <= 16. Tricky optimized form of:
            // if (byte1 > (byte) 0xF4
            //     || byte1 == (byte) 0xF0 && byte2 < (byte) 0x90
            //     || byte1 == (byte) 0xF4 && byte2 > (byte) 0x8F)
            || (((byte1 << 28) + (byte2 - (byte) 0x90)) >> 30) != 0
            // Third byte trailing-byte test
            || bytes[index++] > (byte) 0xBF
            // Fourth byte trailing-byte test
            || bytes[index++] > (byte) 0xBF) {
          return false;
        }
      }
    }
  }
}

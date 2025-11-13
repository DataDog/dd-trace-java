package datadog.trace.api.http;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @see StoredCharBody
 */
public class StoredByteBody implements StoredBodySupplier {

  private static final Logger LOGGER = LoggerFactory.getLogger(StoredByteBody.class);

  static final Charset UTF_8 = StandardCharsets.UTF_8;
  static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

  private final ByteBuffer undecodedData = ByteBuffer.allocate(64);
  // decoded data has double the size to allow for the (unlikely)
  // prospect that supplementary characters (2 chars) be encoded in 1 byte
  private final CharBuffer decodedData = CharBuffer.allocate(128);
  private CharsetDecoder charsetDecoder;
  private StoredCharBody storedCharBody;

  public StoredByteBody(
      RequestContext requestContext,
      BiFunction<RequestContext, StoredBodySupplier, Void> startCb,
      BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb,
      @Nullable Charset charset,
      int lengthHint) {
    if (charset != null) {
      this.charsetDecoder = ThreadLocalCoders.decoderFor(charset);
    }
    this.storedCharBody = new StoredCharBody(requestContext, startCb, endCb, lengthHint, this);
  }

  public synchronized void appendData(byte[] bytes, int start, int end) {
    try {
      if (storedCharBody.isLimitReached()) {
        return;
      }
      for (int i = start; i < end; ) {
        if (!undecodedData.hasRemaining()) {
          commit(false);
        }
        int write = Math.min(end - i, undecodedData.remaining());
        undecodedData.put(bytes, i, write);
        i += write;
      }
    } catch (final Throwable e) {
      LOGGER.debug("Failed to append byte array chunk", e);
    }
    storedCharBody.maybeNotifyStart();
  }

  /**
   * Writes up to <code>len</code> bytes, in general through several invocations of the callback
   * <code>cb</code>. The callback must write exactly <code>undecodedData.remaining()</code> bytes
   * on each invocation. The limit of <code>undecodedData</code> passed to the callback will be
   * adjusted in the last iteration, if necessary.
   *
   * @param cb the callback used to write directly into undecodedData
   * @param len the amount of data available to write
   */
  public synchronized void appendData(ByteBufferWriteCallback cb, int len) {
    try {
      for (int i = 0; i < len; ) {
        if (storedCharBody.isLimitReached()) {
          return;
        }
        if (!undecodedData.hasRemaining()) {
          commit(false);
        }
        int left = len - i;
        int remainingInUndecoded = undecodedData.remaining();
        if (remainingInUndecoded > left) {
          undecodedData.limit(left);
          i += left;
        } else {
          i += remainingInUndecoded;
        }
        cb.put(undecodedData);
      }
      undecodedData.limit(undecodedData.capacity());

    } catch (final Throwable e) {
      LOGGER.debug("Failed to append byte buffer callback", e);
    }
    storedCharBody.maybeNotifyStart();
  }

  public synchronized void appendData(int byteValue) {
    try {
      if (storedCharBody.isLimitReached()) {
        return;
      }
      if (byteValue < 0 || byteValue > 255) {
        return;
      }

      if (!undecodedData.hasRemaining()) {
        commit(false);
      }
      undecodedData.put((byte) byteValue);
    } catch (final Throwable e) {
      LOGGER.debug("Failed to append byte", e);
    }
    storedCharBody.maybeNotifyStart();
  }

  public synchronized void setCharset(Charset charset) {
    this.charsetDecoder = ThreadLocalCoders.decoderFor(charset);
  }

  public Flow<Void> maybeNotify() {
    try {
      commit(true);
    } catch (final Throwable e) {
      LOGGER.debug("Failed to commit end of input", e);
    }
    return storedCharBody.maybeNotify();
  }

  // may throw BlockingException. If used directly in advice, make use of @Advice.Throwable to
  // propagate
  public void maybeNotifyAndBlock() {
    try {
      commit(true);
    } catch (final Throwable e) {
      LOGGER.debug("Failed to commit end of input", e);
    }
    storedCharBody.maybeNotifyAndBlock();
  }

  @Override
  public synchronized CharSequence get() {
    commit(false);
    return storedCharBody.get();
  }

  private void commit(boolean endOfInput) {
    if (undecodedData.position() == 0) {
      return;
    }
    if (charsetDecoder == null) {
      charsetDecoder =
          ThreadLocalCoders.decoderFor(UTF_8).onMalformedInput(CodingErrorAction.REPORT);
    }

    this.undecodedData.flip();
    CoderResult decode = charsetDecoder.decode(this.undecodedData, this.decodedData, endOfInput);
    if (endOfInput) {
      /**
       * Ensure the decoder is at a proper state in case the original input stream is reset,
       * otherwise we will face: java.lang.IllegalStateException: Current state = CODING_END, new
       * state = CODING
       */
      charsetDecoder.reset();
    }

    this.decodedData.flip();
    this.storedCharBody.appendData(this.decodedData);
    this.decodedData.position(0);
    this.decodedData.limit(this.decodedData.capacity());

    this.undecodedData.compact();

    if (decode.isError()) {
      // should only happen if charset was not explicitly given,
      // as o/wise we use repl. chars
      reencodeAsLatin1();
      this.charsetDecoder = ThreadLocalCoders.decoderFor(ISO_8859_1);
      commit(endOfInput);
    }
  }

  private void reencodeAsLatin1() {
    CharBuffer curData = this.storedCharBody.get();
    // reinterpreting the UTF-8 decoded sequence as latin1 will
    // possibly result in a bigger result in terms of code points,
    // so we need to make a copy

    CharsetEncoder encoder = ThreadLocalCoders.utf8Encoder();
    ByteBuffer utf8Encoded;
    try {
      utf8Encoded = encoder.encode(curData);
    } catch (CharacterCodingException e) {
      throw new UndeclaredThrowableException(e); // can't happen
    }

    this.storedCharBody.dropData();

    int limit = utf8Encoded.limit();
    for (int i = 0; i < limit; i++) {
      // & to reverse the sign extension on the int promotion
      this.storedCharBody.appendData(utf8Encoded.get(i) & 0xFF);
    }
  }

  public interface ByteBufferWriteCallback {
    /**
     * Asks the callback of {@link StoredByteBody#appendData(ByteBufferWriteCallback, int)} to write
     * exactly <code>undecodedData.remaining()</code> bytes into the passed <code>ByteBuffer</code>.
     * This amount of bytes will never be larger that the amount of data yet to be written; put
     * another way, for one invocation of <code>appendData(cb, n)</code>, the sum of the values of
     * <code>undecodedData.remaining()</code> for the several <code>cb</code> calls will be <code>n
     * </code> (or zero, if alternatively, cb is never invoked by <code>appendData</code>).
     *
     * @param undecodedData the buffer to write into
     */
    void put(ByteBuffer undecodedData);
  }
}

// adapted from sun.nio.cs
class ThreadLocalCoders {
  private static final int CACHE_SIZE = 3;

  private abstract static class Cache {

    // Thread-local reference to array of cached objects, in LRU order
    private final ThreadLocal<Object[]> cache = new ThreadLocal<>();
    private final int size;

    Cache(int size) {
      this.size = size;
    }

    abstract Object create(Object name);

    private void moveToFront(Object[] oa, int i) {
      Object ob = oa[i];
      for (int j = i; j > 0; j--) oa[j] = oa[j - 1];
      oa[0] = ob;
    }

    abstract boolean hasName(Object ob, Object name);

    Object forName(Object name) {
      Object[] oa = cache.get();
      if (oa == null) {
        oa = new Object[size];
        cache.set(oa);
      } else {
        for (int i = 0; i < oa.length; i++) {
          Object ob = oa[i];
          if (ob == null) continue;
          if (hasName(ob, name)) {
            if (i > 0) moveToFront(oa, i);
            return ob;
          }
        }
      }

      // Create a new object
      Object ob = create(name);
      oa[oa.length - 1] = ob;
      moveToFront(oa, oa.length - 1);
      return ob;
    }
  }

  private static final Cache DECODER_CACHE =
      new Cache(CACHE_SIZE) {
        boolean hasName(Object ob, Object name) {
          return ((CharsetDecoder) ob).charset().equals(name);
        }

        Object create(Object charset) {
          return ((Charset) charset).newDecoder().onUnmappableCharacter(CodingErrorAction.REPLACE);
        }
      };

  public static CharsetDecoder decoderFor(Charset charset) {
    CharsetDecoder cd = (CharsetDecoder) DECODER_CACHE.forName(charset);
    cd.onMalformedInput(CodingErrorAction.REPLACE);
    cd.reset();
    return cd;
  }

  private static final ThreadLocal<CharsetEncoder> UTF8_ENCODER_CACHE =
      new ThreadLocal<CharsetEncoder>() {
        @Override
        protected CharsetEncoder initialValue() {
          return StoredByteBody.UTF_8
              .newEncoder()
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .onMalformedInput(CodingErrorAction.REPLACE);
        }
      };

  public static CharsetEncoder utf8Encoder() {
    CharsetEncoder charsetEncoder = UTF8_ENCODER_CACHE.get();
    charsetEncoder.reset();
    return charsetEncoder;
  }
}

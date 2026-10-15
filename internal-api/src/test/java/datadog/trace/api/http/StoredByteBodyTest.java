package datadog.trace.api.http;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoredByteBodyTest {

  private static final int STORE_LIMIT = 128 * 1024;

  private RequestContext requestContext;
  private BiFunction<RequestContext, StoredBodySupplier, Void> startCb;
  private BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> endCb;

  private StoredByteBody storedByteBody;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    requestContext = mock(RequestContext.class);
    startCb = mock(BiFunction.class);
    endCb = mock(BiFunction.class);
  }

  @Test
  void basicTestWithNoBufferExtension() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);
    @SuppressWarnings("unchecked")
    Flow<Void> mockFlow = mock(Flow.class);

    storedByteBody.appendData((int) 'a');

    verify(startCb).apply(requestContext, storedByteBody);

    when(endCb.apply(requestContext, storedByteBody)).thenReturn(mockFlow);
    storedByteBody.appendData(repeat('a', 127), 0, 127);
    Flow<Void> flow = storedByteBody.maybeNotify();

    verify(endCb).apply(requestContext, storedByteBody);
    assertEquals(repeat("a", 128), storedByteBody.get().toString());
    assertSame(mockFlow, flow);
  }

  @Test
  void testStoreLimit() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData((int) 'a');

    verify(startCb).apply(requestContext, storedByteBody);

    // last byte ignored
    storedByteBody.appendData(repeat('a', STORE_LIMIT), 0, STORE_LIMIT);
    // ignored
    storedByteBody.appendData(0);
    // ignored
    storedByteBody.appendData(new byte[] {0}, 0, 1);

    assertEquals(repeat("a", STORE_LIMIT), storedByteBody.get().toString());
  }

  @Test
  void ignoresInvalidIntegersGivenToAppendData() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(-1);
    storedByteBody.appendData(256);

    assertEquals("", storedByteBody.get().toString());
  }

  @Test
  void wellFormedUtf8Data() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    byte[] data = "\u00E1\u0800\uD800\uDC00".getBytes(UTF_8);
    storedByteBody.appendData(data, 0, data.length);

    assertEquals("\u00E1\u0800\uD800\uDC00", storedByteBody.get().toString());
  }

  @Test
  void nonUtf8DataWithSpecifiedEncoding() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, ISO_8859_1, 0);

    byte[] data = "\u00E1".getBytes(UTF_8);
    storedByteBody.appendData(data, 0, data.length);

    assertEquals("\u00C3\u00A1", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatin1OnFirstByte() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xFF}, 0, 1);

    assertEquals("\u00FF", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatin1OnSecondByte() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xC3, (byte) 0xC3}, 0, 2);

    assertEquals("\u00C3\u00C3", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatin1OnThirdByte() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xE0, (byte) 0x80, 0x7F}, 0, 3);

    assertEquals("\u00E0\u0080\u007F", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatin1OnFourthByte() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xF0, (byte) 0x80, (byte) 0x80, 0x7F}, 0, 4);

    assertEquals("\u00F0\u0080\u0080\u007F", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatinOnUnfinished2ByteSequences() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(0xC3);
    storedByteBody.maybeNotify();

    assertEquals("\u00C3", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatinOnUnfinished3ByteSequences() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xE0, (byte) 0xA0}, 0, 2);
    storedByteBody.maybeNotify();

    assertEquals("\u00E0\u00A0", storedByteBody.get().toString());
  }

  @Test
  void fallbackToLatinOnUnfinished4ByteSequences() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);

    storedByteBody.appendData(new byte[] {(byte) 0xF0, (byte) 0x90, (byte) 0x80}, 0, 3);
    storedByteBody.maybeNotify();

    assertEquals("\u00F0\u0090\u0080", storedByteBody.get().toString());
  }

  @Test
  void utf8DataCanBeReencodedAsLatin1() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);
    byte[] bytes = repeat("\u00E1", 16).getBytes(UTF_8);

    storedByteBody.appendData(bytes, 0, bytes.length);

    assertEquals(repeat("\u00E1", 16), storedByteBody.get().toString());

    storedByteBody.appendData(0x80);
    storedByteBody.appendData(0x80);

    assertEquals(repeat("\u00C3\u00A1", 16) + "\u0080\u0080", storedByteBody.get().toString());
  }

  @Test
  void byteBufferAppendVariant() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);
    StoredByteBody.ByteBufferWriteCallback mockCb =
        mock(StoredByteBody.ByteBufferWriteCallback.class);
    AtomicInteger iteration = new AtomicInteger(1);
    doAnswer(
            invocation -> {
              ByteBuffer byteBuffer = invocation.getArgument(0);
              if (iteration.getAndIncrement() == 1) {
                assertEquals(64, byteBuffer.remaining());
                byteBuffer.put(repeat("a", 64).getBytes(ISO_8859_1));
              } else {
                assertEquals(4, byteBuffer.remaining());
                byteBuffer.put("0123".getBytes(ISO_8859_1));
              }
              return null;
            })
        .when(mockCb)
        .put(any(ByteBuffer.class));

    storedByteBody.appendData(mockCb, 68);

    verify(mockCb, times(2)).put(any(ByteBuffer.class));
    assertEquals(repeat("a", 64) + "0123", storedByteBody.get().toString());
  }

  @Test
  void byteAppendVariantIsSkippedAfterStoreLimit() {
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, null, 0);
    StoredByteBody.ByteBufferWriteCallback mockCb =
        mock(StoredByteBody.ByteBufferWriteCallback.class);

    storedByteBody.appendData(repeat('a', STORE_LIMIT), 0, STORE_LIMIT);
    storedByteBody.get(); // force commit
    storedByteBody.appendData(mockCb, 1);

    verifyNoInteractions(mockCb);
  }

  @Test
  void doesNotFailOnDoubleCommit() {
    Charset charset = Charset.defaultCharset();
    byte[] body = repeat("hello world", 100).getBytes(charset);
    storedByteBody = new StoredByteBody(requestContext, startCb, endCb, charset, 0);
    storedByteBody.appendData(body, 0, body.length);
    storedByteBody.maybeNotify();

    assertDoesNotThrow(
        () -> {
          storedByteBody.appendData(body, 0, body.length);
          storedByteBody.maybeNotify();
        });
  }

  private static byte[] repeat(char value, int count) {
    byte[] bytes = new byte[count];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static String repeat(String value, int count) {
    StringBuilder builder = new StringBuilder(value.length() * count);
    for (int index = 0; index < count; index++) {
      builder.append(value);
    }
    return builder.toString();
  }
}

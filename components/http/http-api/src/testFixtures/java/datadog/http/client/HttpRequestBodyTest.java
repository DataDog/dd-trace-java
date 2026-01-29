package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

public class HttpRequestBodyTest {

  // TODO Test empty string
  // TODO Test empty byte array
  // TODO Test empty ByteBuffer list

  @Test
  void testNullString() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((String) null));
  }

  @Test
  void testNullBytes() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((byte[]) null));
  }

  @Test
  void testNullByteBuffer() {
    assertThrows(NullPointerException.class, () -> HttpRequestBody.of((List<ByteBuffer>) null));
  }
}

package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PayloadBodyTest {

  // msgpack array header for 2 traces, then the two body parts
  private static final byte[] EXPECTED = {(byte) 0x92, (byte) 0x91, 1, (byte) 0x91, 2};

  @Test
  void bodyInPartsIsWrittenInOrderAndCanBeWrittenAgain() throws IOException {
    Payload payload = newPayload();

    assertEquals(EXPECTED.length, payload.sizeInBytes());
    assertArrayEquals(EXPECTED, writeTo(payload));
    // a retried send writes the whole body again
    assertArrayEquals(EXPECTED, writeTo(payload));
    assertEquals(EXPECTED.length, payload.sizeInBytes());
  }

  private static Payload newPayload() {
    return new TraceMapperV0_4()
        .newPayload()
        .withBody(
            2,
            Arrays.asList(
                ByteBuffer.wrap(new byte[] {(byte) 0x91, 1}),
                ByteBuffer.wrap(new byte[] {(byte) 0x91, 2})));
  }

  private static byte[] writeTo(Payload payload) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    payload.writeTo(Channels.newChannel(out));
    return out.toByteArray();
  }
}

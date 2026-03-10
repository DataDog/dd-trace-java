package datadog.trace.test.agent.decoder;

import datadog.trace.test.agent.decoder.v04.raw.MessageV04;
import datadog.trace.test.agent.decoder.v05.raw.MessageV05;
import datadog.trace.test.agent.decoder.v1.raw.MessageV1;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Decoder {
  public static DecodedMessage decode(ByteBuffer buffer) {
    return MessageV05.unpack(buffer);
  }

  public static DecodedMessage decode(byte[] buffer) {
    return MessageV05.unpack(buffer);
  }

  public static DecodedMessage decodeV04(byte[] buffer) {
    return MessageV04.unpack(buffer);
  }

  public static DecodedMessage decodeV1(byte[] buffer) {
    return MessageV1.unpack(buffer);
  }

  public static List<DecodedSpan> sortByStart(Collection<DecodedSpan> spans) {
    DecodedSpan[] spanArray = new DecodedSpan[spans.size()];
    spanArray = spans.toArray(spanArray);
    Arrays.sort(
        spanArray,
        (o1, o2) -> {
          long res = o1.getStart() - o2.getStart();
          return res == 0 ? 0 : res > 0 ? 1 : -1;
        });
    return Arrays.asList(spanArray);
  }
}

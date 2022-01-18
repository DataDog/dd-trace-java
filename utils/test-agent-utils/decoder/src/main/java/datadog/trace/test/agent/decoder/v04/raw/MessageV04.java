package datadog.trace.test.agent.decoder.v04.raw;

import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class MessageV04 implements DecodedMessage {
  public static MessageV04 unpack(ByteBuffer buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  public static MessageV04 unpack(byte[] buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  static MessageV04 unpack(MessageUnpacker unpacker) {
    try {
      DecodedTrace[] traces = TraceV04.unpackTraces(unpacker);
      return new MessageV04(traces);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  private final DecodedTrace[] traces;

  private MessageV04(DecodedTrace[] traces) {
    this.traces = traces;
  }

  @Override
  public List<DecodedTrace> getTraces() {
    if (traces.length == 0) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(Arrays.asList(traces));
  }
}

package datadog.trace.test.agent.decoder.v05.raw;

import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class MessageV05 implements DecodedMessage {
  public static MessageV05 unpack(ByteBuffer buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  public static MessageV05 unpack(byte[] buffer) {
    return unpack(MessagePack.DEFAULT_UNPACKER_CONFIG.newUnpacker(buffer));
  }

  static MessageV05 unpack(MessageUnpacker unpacker) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size != 2) {
        throw new IllegalArgumentException("MessageV05 array size " + size + " != 2");
      }
      DictionaryV05 dictionary = DictionaryV05.unpack(unpacker);
      DecodedTrace[] traces = TraceV05.unpackTraces(unpacker, dictionary);
      return new MessageV05(traces);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  private final DecodedTrace[] traces;

  private MessageV05(DecodedTrace[] traces) {
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

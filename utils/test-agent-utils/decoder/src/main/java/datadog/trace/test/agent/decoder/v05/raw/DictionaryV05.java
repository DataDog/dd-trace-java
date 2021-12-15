package datadog.trace.test.agent.decoder.v05.raw;

import org.msgpack.core.MessageUnpacker;

public class DictionaryV05 {
  static DictionaryV05 unpack(MessageUnpacker unpacker) {
    try {
      int size = unpacker.unpackArrayHeader();
      if (size < 0) {
        throw new IllegalArgumentException("Negative dictionary size " + size);
      }
      String[] strings = new String[size];
      for (int i = 0; i < size; i++) {
        strings[i] = unpacker.unpackString();
      }
      return new DictionaryV05(strings);
    } catch (Throwable t) {
      if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new IllegalArgumentException(t);
      }
    }
  }

  private final String[] strings;

  public DictionaryV05(String[] strings) {
    this.strings = strings;
  }

  public String at(int index) {
    return strings[index];
  }
}

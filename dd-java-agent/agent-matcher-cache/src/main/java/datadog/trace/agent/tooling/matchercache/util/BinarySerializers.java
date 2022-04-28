package datadog.trace.agent.tooling.matchercache.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class BinarySerializers {

  public static void writeInt(OutputStream os, int value) throws IOException {
    os.write(value >>> 24);
    os.write(value >>> 16);
    os.write(value >>> 8);
    os.write(value);
  }

  public static int readInt(InputStream is) throws IOException {
    return (is.read() & 0xff) << 24
        | (is.read() & 0xff) << 16
        | (is.read() & 0xff) << 8
        | is.read() & 0xff;
  }

  public static void writeString(OutputStream os, String str) throws IOException {
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    writeInt(os, bytes.length);
    os.write('\n');
    os.write(bytes);
  }

  public static String readString(InputStream is) throws IOException {
    int length = readInt(is);
    if (length == 0) {
      return "";
    }
    byte[] bytes = new byte[length];
    int newLineChar = is.read();
    if (newLineChar != '\n') {
      throw new IllegalStateException("Expected \\n but read " + (char) newLineChar);
    }
    int len = is.read(bytes, 0, length);
    return new String(bytes, 0, len, StandardCharsets.UTF_8);
  }
}

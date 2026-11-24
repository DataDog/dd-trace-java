package datadog.trace.civisibility.coverage.probe.fixtures;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

/** Drives okio, whose classes the line coverage tests instrument too. */
public final class OkioWorkload {

  public static final int SCENARIOS = 6;

  public static Object run(int scenario) throws IOException {
    switch (scenario) {
      case 0:
        return new Buffer().writeUtf8("hello, world").readUtf8(5);
      case 1:
        return ByteString.encodeUtf8("coverage").sha256().hex();
      case 2:
        try {
          return new Buffer().writeByte(1).readInt();
        } catch (Exception e) {
          // Kotlin declares no checked exception here, so javac does not allow catching
          // EOFException
          return e.getClass().getName();
        }
      case 3:
        BufferedSource source =
            Okio.buffer(Okio.source(new ByteArrayInputStream("a\nbb\nccc".getBytes("UTF-8"))));
        StringBuilder lines = new StringBuilder();
        for (String line; (line = source.readUtf8Line()) != null; ) {
          lines.append(line.length());
        }
        return lines.toString();
      case 4:
        Buffer buffer = new Buffer();
        for (int i = 0; i < 20000; i++) {
          buffer.writeDecimalLong(i).writeByte(',');
        }
        return buffer.size() + ":" + buffer.indexOf((byte) '9');
      case 5:
        return ByteString.decodeBase64("Y292ZXJhZ2U=").utf8() + ByteString.decodeHex("zz");
      default:
        throw new IllegalArgumentException("no scenario " + scenario);
    }
  }

  private OkioWorkload() {}
}

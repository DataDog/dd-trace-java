package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

public abstract class TestIdentifierSerializer {

  private static final Charset CHARSET = StandardCharsets.UTF_8;

  public static ByteBuffer serialize(Collection<TestIdentifier> testIdentifiers) {
    if (testIdentifiers == null || testIdentifiers.isEmpty()) {
      ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
      buffer.putInt(0);
      buffer.flip();
      return buffer;
    }

    int length =
        Integer.BYTES // testIdentifiers.size()
            // suite, name, parameters length() for each test
            + testIdentifiers.size() * 3 * Integer.BYTES;
    for (TestIdentifier test : testIdentifiers) {
      String suite = test.getSuite();
      String name = test.getName();
      String parameters = test.getParameters();

      length += suite.getBytes(CHARSET).length;
      length += name.getBytes(CHARSET).length;
      if (parameters != null) {
        length += parameters.getBytes(CHARSET).length;
      }
    }

    ByteBuffer buffer = ByteBuffer.allocate(length);
    buffer.putInt(testIdentifiers.size());

    for (TestIdentifier test : testIdentifiers) {
      String suite = test.getSuite();
      String name = test.getName();
      String parameters = test.getParameters();

      byte[] suiteBytes = suite.getBytes(CHARSET);
      buffer.putInt(suiteBytes.length);
      buffer.put(suiteBytes);

      byte[] nameBytes = name.getBytes(CHARSET);
      buffer.putInt(nameBytes.length);
      buffer.put(nameBytes);

      if (parameters != null) {
        byte[] parametersBytes = parameters.getBytes(CHARSET);
        buffer.putInt(parametersBytes.length);
        buffer.put(parametersBytes);
      } else {
        buffer.putInt(-1);
      }
    }

    buffer.flip();
    return buffer;
  }

  public static Collection<TestIdentifier> deserialize(ByteBuffer buffer) {
    int count = buffer.getInt();
    Collection<TestIdentifier> tests = new ArrayList<>(count);
    while (count-- > 0) {
      int suiteLength = buffer.getInt();
      byte[] suiteBytes = new byte[suiteLength];
      buffer.get(suiteBytes);
      String suite = new String(suiteBytes, CHARSET);

      int nameLength = buffer.getInt();
      byte[] nameBytes = new byte[nameLength];
      buffer.get(nameBytes);
      String name = new String(nameBytes, CHARSET);

      String parameters;
      int parametersLength = buffer.getInt();
      if (parametersLength >= 0) {
        byte[] parametersBytes = new byte[parametersLength];
        buffer.get(parametersBytes);
        parameters = new String(parametersBytes, CHARSET);
      } else {
        parameters = null;
      }

      tests.add(new TestIdentifier(suite, name, parameters, null));
    }
    return tests;
  }
}

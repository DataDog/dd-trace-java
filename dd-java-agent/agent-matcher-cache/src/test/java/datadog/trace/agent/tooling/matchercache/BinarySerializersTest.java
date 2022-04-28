package datadog.trace.agent.tooling.matchercache;

import static datadog.trace.agent.tooling.matchercache.util.BinarySerializers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.*;
import org.junit.jupiter.api.Test;

public class BinarySerializersTest {

  @Test
  public void testIntSerializer() throws IOException {
    assertEquals(0, writeAndReadInt(0));
    assertEquals(Integer.MAX_VALUE, writeAndReadInt(Integer.MAX_VALUE));
    assertEquals(Integer.MIN_VALUE, writeAndReadInt(Integer.MIN_VALUE));
  }

  @Test
  public void testStringSerializer() throws IOException {
    assertEquals("", writeAndReadString(""));
    assertEquals("H", writeAndReadString("H"));
    assertEquals("AbcDef", writeAndReadString("AbcDef"));
    assertEquals("Combien ça coûte?", writeAndReadString("Combien ça coûte?"));
  }

  private int writeAndReadInt(int value) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    writeInt(os, value);

    return readInt(new ByteArrayInputStream(os.toByteArray()));
  }

  private String writeAndReadString(String value) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    writeString(os, value);

    return readString(new ByteArrayInputStream(os.toByteArray()));
  }
}

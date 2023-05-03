package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.communication.serialization.msgpack.MsgPackWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class SmokeTest {

  static class Foo {
    long id1;
    long id2;
    String name;
    int[] values;
    Map<String, Object> tags;

    static Foo create() {
      Foo foo = new Foo();
      foo.id1 = ThreadLocalRandom.current().nextLong();
      foo.id2 = ThreadLocalRandom.current().nextLong();
      foo.name = UUID.randomUUID().toString();
      foo.values = new int[10];
      Arrays.fill(foo.values, ThreadLocalRandom.current().nextInt());
      foo.tags = new HashMap<>();
      foo.tags.put("foo", 9f);
      foo.tags.put("bar", 9d);
      foo.tags.put("qux", "tag");
      foo.tags.put("provoke size recalculation", "道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道道");
      foo.tags.put("provoke re-encoding", "Straßenschilder");
      foo.tags.put("list", Collections.singletonList("element"));
      return foo;
    }
  }

  Map<? extends CharSequence, byte[]> constantPool =
      new HashMap<CharSequence, byte[]>() {
        {
          put("foo", "foo".getBytes(StandardCharsets.UTF_8));
          put("id1", "id1".getBytes(StandardCharsets.UTF_8));
        }
      };
  EncodingCache encodingCache = null;

  @Test
  public void testWriteMessage() {
    final Foo message = Foo.create();
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                1024,
                (messageCount, buffy) -> {
                  assertEquals(1, messageCount);
                  try {
                    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffy);
                    assertEquals("id1", unpacker.unpackString());
                    assertEquals(message.id1, unpacker.unpackLong());
                    assertEquals("id2", unpacker.unpackString());
                    assertEquals(message.id2, unpacker.unpackLong());
                    assertEquals("name", unpacker.unpackString());
                    assertEquals(message.name, unpacker.unpackString());
                    assertEquals("values", unpacker.unpackString());
                    int length = unpacker.unpackArrayHeader();
                    assertEquals(message.values.length, length);
                    for (int i : message.values) {
                      assertEquals(i, unpacker.unpackInt());
                    }
                    assertEquals("tags", unpacker.unpackString());
                    int mapHeader = unpacker.unpackMapHeader();
                    assertEquals(message.tags.size(), mapHeader);
                    for (int i = 0; i < mapHeader; ++i) {
                      String key = unpacker.unpackString();
                      Object expected = message.tags.get(key);
                      assertNotNull(expected);
                      if (expected instanceof Float) {
                        assertEquals((Float) expected, unpacker.unpackFloat(), 0.0001);
                      } else if (expected instanceof Double) {
                        assertEquals((Double) expected, unpacker.unpackDouble(), 0.0001);
                      } else if (expected instanceof List) {
                        List<String> l = (List<String>) expected;
                        assertEquals(l.size(), unpacker.unpackArrayHeader());
                        for (String element : l) {
                          assertEquals(element, unpacker.unpackString());
                        }
                      } else if (expected instanceof String) {
                        assertEquals(expected, unpacker.unpackString());
                      }
                    }
                  } catch (IOException e) {
                    Assertions.fail(e.getMessage());
                  }
                }));
    packer.format(
        message,
        (data, p) -> {
          p.writeString("id1", encodingCache);
          p.writeLong(data.id1);
          p.writeString("id2", encodingCache);
          p.writeLong(data.id2);
          p.writeString("name", encodingCache);
          p.writeString(data.name, encodingCache);
          p.writeString("values", encodingCache);
          p.writeObject(data.values, encodingCache);
          p.writeString("tags", encodingCache);
          p.writeMap(data.tags, encodingCache);
        });
    packer.flush();
  }
}

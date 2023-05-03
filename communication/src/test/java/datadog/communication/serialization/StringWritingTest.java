package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.communication.serialization.msgpack.MsgPackWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class StringWritingTest {

  private static final EncodingCache NO_CACHE = null;

  private static final Map<CharSequence, byte[]> MEMOISATION = new HashMap<>();
  private static final EncodingCache CACHE =
      s -> MEMOISATION.computeIfAbsent(s, s1 -> ((String) s1).getBytes(StandardCharsets.UTF_8));

  private static final int TEN_KB = 10 << 10;

  public static Object[][] maps() {
    return new Object[][] {
      {
        Arrays.asList(
            new HashMap<String, String>() {
              {
                put("english", "bye");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("german", "tschüß");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("hani", "道");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("hani", "道道道");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("CJK", "罿潯罿潯罿潯罿潯罿潯");
              }
            }),
      },
      {
        Arrays.asList(
            new HashMap<String, String>() {
              {
                put("123456789012390-2394-3", "alshjdhlasjhLKASJKLDAHsdlkAHSDKLJAHsdklHASDKSa");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("123456789012390-2394-3", "Straßenschilder");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("hani", "道可道非常道名可名非常名");
                put("foo", "bar");
              }
            })
      },
      {
        Arrays.asList(
            new HashMap<String, String>() {
              {
                put(
                    "123456789012390-2394-3",
                    "ßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßßß");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put("hani", "道可道非常道名可名非常名名名名名名名名名名名");
                put("foo", "bar");
              }
            })
      },
      {
        Arrays.asList(
            new HashMap<String, String>() {
              {
                put("emoji", "\uD83D\uDC4D");
                put("foo", "bar");
              }
            },
            new HashMap<String, String>() {
              {
                put(
                    "emoji",
                    "\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D");
                put("foo", "bar");
              }
            })
      }
    };
  }

  @ParameterizedTest
  @MethodSource("maps")
  public void testSerialiseTextMapWithCache(List<Map<String, String>> maps) {
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(TEN_KB, (messageCount, buffer) -> testBufferContents(buffer, maps)));
    for (Map<String, String> map : maps) {
      packer.format(map, (m, p) -> p.writeMap(m, CACHE));
    }
    packer.flush();
  }

  @ParameterizedTest
  @MethodSource("maps")
  public void testSerialiseTextMapWithoutCache(List<Map<String, String>> maps) {
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(TEN_KB, (messageCount, buffer) -> testBufferContents(buffer, maps)));
    for (Map<String, String> map : maps) {
      packer.format(map, (m, p) -> p.writeMap(m, NO_CACHE));
    }
    packer.flush();
  }

  private void testBufferContents(ByteBuffer buffer, List<Map<String, String>> maps) {
    try {
      MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
      for (Map<String, String> map : maps) {
        int mapSize = unpacker.unpackMapHeader();
        assertEquals(map.size(), mapSize);
        int pos = 0;
        while (pos++ < mapSize) {
          String key = unpacker.unpackString();
          String expectedValue = map.get(key);
          assertNotNull(expectedValue);
          assertEquals(expectedValue, unpacker.unpackString());
        }
      }
    } catch (IOException e) {
      fail(e.getMessage());
    }
  }
}

package datadog.trace.core.serialization.msgpack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.EncodingCache;
import datadog.trace.core.serialization.EncodingCachingStrategies;
import datadog.trace.core.serialization.FlushingBuffer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

@RunWith(Parameterized.class)
public class StringWritingTest {

  private static final EncodingCache NO_CACHE = EncodingCachingStrategies.NO_CACHING;

  private static final Map<CharSequence, byte[]> MEMOISATION = new HashMap<>();
  private static final EncodingCache CACHE =
      new EncodingCache() {
        @Override
        public byte[] encode(CharSequence s) {
          byte[] utf8 = MEMOISATION.get(s);
          if (utf8 == null) {
            utf8 = ((String) s).getBytes(StandardCharsets.UTF_8);
            MEMOISATION.put(s, utf8);
          }
          return utf8;
        }
      };

  private final List<Map<String, String>> maps;
  private final int TEN_KB = 10 << 10;

  public StringWritingTest(List<Map<String, String>> maps) {
    this.maps = maps;
  }

  @Parameterized.Parameters
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

  @Test
  public void testSerialiseTextMapWithCache() {
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                TEN_KB,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    testBufferContents(buffer);
                  }
                }));
    for (Map<String, String> map : maps) {
      packer.format(
          map,
          new Mapper<Map<String, String>>() {
            @Override
            public void map(Map<String, String> m, Writable p) {
              p.writeMap(m, CACHE);
            }
          });
    }
    packer.flush();
  }

  @Test
  public void testSerialiseTextMapWithoutCache() {
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                TEN_KB,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {
                    testBufferContents(buffer);
                  }
                }));
    for (Map<String, String> map : maps) {
      packer.format(
          map,
          new Mapper<Map<String, String>>() {
            @Override
            public void map(Map<String, String> m, Writable p) {
              p.writeMap(m, NO_CACHE);
            }
          });
    }
    packer.flush();
  }

  private void testBufferContents(ByteBuffer buffer) {
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
      Assert.fail(e.getMessage());
    }
  }
}

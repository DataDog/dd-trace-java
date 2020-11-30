package datadog.trace.core.serialization.protobuf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class StringWritingTest {

  private final List<Map<String, String>> maps;
  private final ByteBuffer buffer = ByteBuffer.allocate(10 << 10);

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
  public void testSerialiseTextMap() {
    ProtobufWriter packer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {
                testBufferContents(buffer);
              }
            },
            buffer);
    for (Map<String, String> map : maps) {
      packer.format(
          map,
          new Mapper<Map<String, String>>() {
            @Override
            public void map(Map<String, String> m, Writable p) {
              p.writeMap(m, null);
            }
          });
    }
    packer.flush();
  }

  @After
  public void resetBuffer() {
    buffer.position(0);
    buffer.limit(buffer.capacity());
  }

  private void testBufferContents(ByteBuffer buffer) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(buffer));
      assertTrue(parsed.hasField(maps.size()));
      assertFalse(parsed.hasField(maps.size() + 1));
      int mapIndex = 1;
      for (Map<String, String> map : maps) {
        UnknownFieldSet.Field part = parsed.getField(mapIndex);
        List<ByteString> mapPart = part.getLengthDelimitedList();
        assertEquals(1, mapPart.size());
        UnknownFieldSet recoveredMap = UnknownFieldSet.parseFrom(mapPart.get(0));
        assertTrue(recoveredMap.hasField(map.size() * 2));
        assertFalse(recoveredMap.hasField(map.size() * 2 + 1));
        int mapField = 1;
        for (Map.Entry<String, String> entry : map.entrySet()) {
          UnknownFieldSet.Field key = recoveredMap.getField(mapField);
          UnknownFieldSet.Field value = recoveredMap.getField(mapField + 1);
          assertEquals(
              key.getLengthDelimitedList().get(0).toString(StandardCharsets.UTF_8), entry.getKey());
          assertEquals(
              value.getLengthDelimitedList().get(0).toString(StandardCharsets.UTF_8),
              entry.getValue());
          mapField += 2;
        }
        ++mapIndex;
      }
    } catch (IOException e) {
      Assert.fail(e.getMessage());
    }
  }
}

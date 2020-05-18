package datadog.trace.core.serialization;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.core.DDSpan;
import datadog.trace.core.MapAcceptor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

public class JsonFormatWriter extends FormatWriter<JsonWriter> {
  private static final Moshi MOSHI = new Moshi.Builder().add(DDSpanAdapter.FACTORY).build();

  public static final JsonAdapter<List<DDSpan>> TRACE_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, DDSpan.class));
  public static final JsonAdapter<DDSpan> SPAN_ADAPTER = MOSHI.adapter(DDSpan.class);

  public static JsonFormatWriter JSON_WRITER = new JsonFormatWriter();

  @Override
  public void writeKey(byte[] key, JsonWriter destination) throws IOException {
    destination.name(new String(key));
  }

  @Override
  public void writeListHeader(final int size, final JsonWriter destination) throws IOException {
    destination.beginArray();
  }

  @Override
  public void writeListFooter(final JsonWriter destination) throws IOException {
    destination.endArray();
  }

  @Override
  public void writeMapHeader(final int size, final JsonWriter destination) throws IOException {
    destination.beginObject();
  }

  @Override
  public void writeMapFooter(final JsonWriter destination) throws IOException {
    destination.endObject();
  }

  @Override
  public void writeString(byte[] key, String value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeShort(byte[] key, short value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeByte(byte[] key, byte value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeInt(byte[] key, int value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeLong(byte[] key, long value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeFloat(byte[] key, float value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeDouble(byte[] key, double value, JsonWriter destination) throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  public void writeBigInteger(byte[] key, BigInteger value, JsonWriter destination)
      throws IOException {
    writeKey(key, destination);
    destination.value(value);
  }

  @Override
  protected MapAcceptor<String> getMetaAcceptor(final JsonWriter destination) {
    return new MapAcceptor<String>() {
      @Override
      public void beginMap(int size) {
        try {
          writeMapHeader(size, destination);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void acceptValue(String key, String value) {
        try {
          destination.name(key);
          destination.value(value);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void endMap() {
        try {
          writeMapFooter(destination);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  static class DDSpanAdapter extends JsonAdapter<DDSpan> {
    public static final JsonAdapter.Factory FACTORY =
        new JsonAdapter.Factory() {
          @Override
          public JsonAdapter<?> create(
              final Type type, final Set<? extends Annotation> annotations, final Moshi moshi) {
            final Class<?> rawType = Types.getRawType(type);
            if (rawType.isAssignableFrom(DDSpan.class)) {
              return new DDSpanAdapter();
            }
            return null;
          }
        };

    @Override
    public DDSpan fromJson(final JsonReader reader) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void toJson(final JsonWriter writer, final DDSpan value) throws IOException {
      JSON_WRITER.writeDDSpan(value, writer);
    }
  }
}

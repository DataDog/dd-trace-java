package com.datadog.appsec.util;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_DEPTH;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.ddwaf.WAFModule.MAX_STRING_SIZE;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.api.appsec.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import okio.Okio;

public interface BodyParser {

  Object parse(State state, InputStream inputStream);

  static BodyParser forJson() {
    return JsonParser.INSTANCE;
  }

  static BodyParser forMediaType(final MediaType type) {
    if (type.isJson()) {
      return JsonParser.INSTANCE;
    }
    return null;
  }

  static BodyParser forMediaType(final String type) {
    return forMediaType(MediaType.parse(type));
  }

  class State {
    private int elemsLeft = MAX_ELEMENTS;
    public boolean objectTooDeep = false;
    public boolean listMapTooLarge = false;
    public boolean stringTooLong = false;
  }

  class JsonParser implements BodyParser {

    private static final BodyParser INSTANCE = new JsonParser();

    @Override
    public Object parse(final State state, final InputStream inputStream) {
      try {
        final JsonAdapter<Object> adapter = new BoundedObjectAdapter(state);
        return adapter.fromJson(Okio.buffer(Okio.source(inputStream)));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private static final class BoundedObjectAdapter extends JsonAdapter<Object> {

      private final State state;

      public BoundedObjectAdapter(final State state) {
        this.state = state;
      }

      @Generated
      @Override
      public void toJson(final JsonWriter writer, @Nullable final Object value) throws IOException {
        throw new UnsupportedOperationException("Parsing-only adapter");
      }

      @Override
      public Object fromJson(final JsonReader reader) throws IOException {
        return readValue(reader, 0);
      }

      private Object readValue(final JsonReader r, final int depth) throws IOException {
        if (depth >= MAX_DEPTH) {
          state.objectTooDeep = true;
          r.skipValue();
          return null;
        }

        if (state.elemsLeft-- == 0) {
          state.listMapTooLarge = true;
          r.skipValue();
          return null;
        }

        switch (r.peek()) {
          case BEGIN_OBJECT:
            return readObject(r, depth);
          case BEGIN_ARRAY:
            return readArray(r, depth);
          case STRING:
            String value = r.nextString();
            if (value.length() > MAX_STRING_SIZE) {
              state.stringTooLong = true;
              value = value.substring(0, MAX_STRING_SIZE);
            }
            return value;
          case NUMBER:
            return r.nextDouble();
          case BOOLEAN:
            return r.nextBoolean();
          case NULL:
            return r.nextNull();
          default:
            throw new JsonDataException("Unexpected token at value boundary: " + r.peek());
        }
      }

      private Map<String, Object> readObject(final JsonReader r, final int depth)
          throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        r.beginObject();
        while (r.hasNext()) {
          String name = r.nextName();
          Object val = readValue(r, depth + 1);
          if (!state.listMapTooLarge) {
            map.put(name, val);
          }
        }
        r.endObject();
        return map;
      }

      private List<Object> readArray(final JsonReader r, final int depth) throws IOException {
        List<Object> list = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) {
          Object value = readValue(r, depth + 1);
          if (!state.listMapTooLarge) {
            list.add(value);
          }
        }
        r.endArray();
        return list;
      }
    }
  }
}

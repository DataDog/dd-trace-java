package com.datadog.debugger.util;

import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ELEMENTS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.ENTRIES;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELDS;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;

import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import okio.Buffer;

/** Reduces the serialized snapshot by removing last depth level (fields or collections) */
public class SnapshotSlicer {

  private final Deque<JsonReader.Token> tokenLevels = new ArrayDeque<>(32);
  private final Deque<Integer> fieldsLevels = new ArrayDeque<>(32);
  private final int maxDepth;
  private boolean copy = true;
  private int skipTokenLevel = -1;
  private JsonReader jsonReader;
  private JsonWriter jsonWriter;

  public static String slice(int maxDepth, String snapshot) {
    return new SnapshotSlicer(maxDepth).internalSlice(snapshot);
  }

  private SnapshotSlicer(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  private String internalSlice(String snapshot) {
    try {
      Buffer writeBuffer = new Buffer();
      jsonWriter = JsonWriter.of(writeBuffer);
      jsonReader = JsonReader.of(new Buffer().writeUtf8(snapshot));
      while (jsonReader.hasNext()) {
        JsonReader.Token token = jsonReader.peek();
        processToken(token);
        handleLevelEnd();
      }
      return writeBuffer.readUtf8();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private void processToken(JsonReader.Token token) throws IOException {
    switch (token) {
      case BEGIN_ARRAY:
        tokenLevels.addLast(token);
        jsonReader.beginArray();
        if (copy) {
          jsonWriter.beginArray();
        }
        break;
      case BEGIN_OBJECT:
        tokenLevels.addLast(token);
        jsonReader.beginObject();
        if (copy) {
          jsonWriter.beginObject();
        }
        break;
      case NAME:
        String name = jsonReader.nextName();
        if (FIELDS.equals(name)) {
          fieldsLevels.addLast(tokenLevels.size());
          if (copy && fieldsLevels.size() > maxDepth) {
            jsonWriter.name(NOT_CAPTURED_REASON);
            jsonWriter.value(DEPTH_REASON);
            copy = false;
            skipTokenLevel = tokenLevels.size();
          }
        }
        if (ELEMENTS.equals(name) || ENTRIES.equals(name)) {
          fieldsLevels.addLast(tokenLevels.size());
        }
        if (copy) {
          jsonWriter.name(name);
        }
        break;
      case BOOLEAN:
        boolean b = jsonReader.nextBoolean();
        if (copy) {
          jsonWriter.value(b);
        }
        break;
      case NULL:
        jsonReader.nextNull();
        if (copy) {
          jsonWriter.nullValue();
        }
        break;
      case NUMBER:
        // Moshi always consider numbers as decimal.
        // need to parse it as string and detect if dot is present
        // or not to determine ints/longs vs doubles
        String numberStrValue = jsonReader.nextString();
        if (copy) {
          if (numberStrValue.indexOf('.') > 0) {
            jsonWriter.value(Double.parseDouble(numberStrValue));
          } else {
            jsonWriter.value(Long.parseLong(numberStrValue));
          }
        }
        break;
      case STRING:
        String s = jsonReader.nextString();
        if (copy) {
          jsonWriter.value(s);
        }
        break;
      default:
        throw new UnsupportedOperationException("Unsupported token: " + token);
    }
  }

  private void handleLevelEnd() throws IOException {
    while (!jsonReader.hasNext() && !tokenLevels.isEmpty()) {
      JsonReader.Token lastToken = tokenLevels.removeLast();
      switch (lastToken) {
        case BEGIN_ARRAY:
          jsonReader.endArray();
          if (copy) {
            jsonWriter.endArray();
          }
          break;
        case BEGIN_OBJECT:
          jsonReader.endObject();
          if (copy) {
            jsonWriter.endObject();
          }
          if (!fieldsLevels.isEmpty() && tokenLevels.size() == fieldsLevels.peekLast().intValue()) {
            fieldsLevels.removeLast();
          }
          if (!copy && skipTokenLevel == tokenLevels.size()) {
            copy = true;
            skipTokenLevel = -1;
          }
          break;
        default:
          throw new UnsupportedOperationException("Unsupported last token: " + lastToken);
      }
    }
  }
}

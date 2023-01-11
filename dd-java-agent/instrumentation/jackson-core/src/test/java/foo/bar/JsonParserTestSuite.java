package foo.bar;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;

public class JsonParserTestSuite {

  private static final String JSON_STRING =
      "{\"key1\":\"value1\",\"key2\":\"value2\",\"key3\":\"value3\"}";

  private JsonParser jsonParser;

  public JsonParserTestSuite(JsonParser jsonParser) {
    this.jsonParser = jsonParser;
  }

  public String currentName() throws IOException {
    return jsonParser.currentName();
  }

  public String getCurrentName() throws IOException {
    return jsonParser.getCurrentName();
  }

  public String getText() throws IOException {
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      return jsonParser.getText();
    }
    return null;
  }

  public String getValueAsString() throws IOException {
    return jsonParser.getValueAsString();
  }

  /*
  public String getValueAsString(final String def) throws IOException {
    JsonParser jsonParser = jsonFactory.createParser(JSON_STRING);
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      return jsonParser.getValueAsString(def);
    }
    return null;
  }
   */

  public String nextFieldName() throws IOException {
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      return jsonParser.nextFieldName();
    }
    return null;
  }

  public String nextTextValue() throws IOException {
    return jsonParser.nextTextValue();
  }
}

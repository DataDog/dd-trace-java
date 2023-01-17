package foo.bar;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;

public class JsonParserTestSuite {

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
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      String value = jsonParser.getValueAsString();
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  public String getValueAsString(final String def) throws IOException {
    while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
      String value = jsonParser.getValueAsString(def);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

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

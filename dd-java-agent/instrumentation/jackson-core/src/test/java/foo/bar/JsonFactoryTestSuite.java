package foo.bar;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

public class JsonFactoryTestSuite {

  private JsonFactory jsonFactory;

  public JsonFactoryTestSuite(final JsonFactory jsonFactory) {
    this.jsonFactory = jsonFactory;
  }

  public JsonParser createParser(final String content) throws IOException {
    return jsonFactory.createParser(content);
  }

  public JsonParser createParser(final InputStream is) throws IOException {
    return jsonFactory.createParser(is);
  }

  public JsonParser createParser(final Reader reader) throws IOException {
    return jsonFactory.createParser(reader);
  }
}

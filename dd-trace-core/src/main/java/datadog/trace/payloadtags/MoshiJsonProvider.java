package datadog.trace.payloadtags;

import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.spi.json.AbstractJsonProvider;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import okio.BufferedSource;
import okio.Okio;

public class MoshiJsonProvider extends AbstractJsonProvider {
  private final Moshi moshi = new Moshi.Builder().build();
  private final JsonAdapter<Object> jsonAdapter = moshi.adapter(Object.class).lenient();

  @Override
  public Object parse(String s) throws InvalidJsonException {
    try {
      return jsonAdapter.fromJson(s);
    } catch (IOException e) {
      throw new InvalidJsonException(e);
    }
  }

  @Override
  public Object parse(InputStream inputStream, String s) throws InvalidJsonException {
    try (BufferedSource source = Okio.buffer(Okio.source(inputStream))) {
      return jsonAdapter.fromJson(source);
    } catch (IOException e) {
      throw new InvalidJsonException(e);
    }
  }

  @Override
  public String toJson(Object o) {
    return jsonAdapter.toJson(o);
  }

  public List<Object> createArray() {
    return new LinkedList<>();
  }

  public Object createMap() {
    return new LinkedHashMap<>();
  }
}

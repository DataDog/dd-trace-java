package datadog.remote_config.tuf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import okio.ByteString;
import okio.Okio;

public class RawJsonAdapter extends JsonAdapter<ByteString> {
  public void toJson(JsonWriter writer, ByteString value) throws IOException {
    if (value == null) {
      writer.nullValue();
      return;
    }
    writer.value(Okio.buffer(Okio.source(new ByteArrayInputStream(value.toByteArray()))));
  }

  public ByteString fromJson(JsonReader reader) throws IOException {
    return reader.nextSource().readByteString();
  }
}

package datadog.remote_config.tuf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.annotation.Nullable;

public class InstantJsonAdapter extends JsonAdapter<Instant> {
  @Nullable
  @Override
  public Instant fromJson(JsonReader reader) throws IOException {
    String s = reader.nextString();
    return OffsetDateTime.parse(s).toInstant();
  }

  @Override
  public void toJson(JsonWriter writer, @Nullable Instant value) throws IOException {
    throw new UnsupportedOperationException();
  }
}

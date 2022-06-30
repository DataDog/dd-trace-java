package datadog.remote_config.tuf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.remote_config.ConfigurationDeserializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import okio.ByteString;
import okio.Okio;

public class FeaturesConfig {
  private final Map<String, ByteString> data;

  public FeaturesConfig(Map<String, ByteString> data) {
    this.data = data;
  }

  public Collection<String> getProducts() {
    return this.data.keySet();
  }

  public byte[] getProductFeaturesByteArray(String product) {
    ByteString byteString = this.data.get(product);
    if (byteString == null) {
      return null;
    }
    return byteString.toByteArray();
  }

  public static class FeaturesConfigDeserializer
      implements ConfigurationDeserializer<FeaturesConfig> {
    private final JsonAdapter<Map<String, ByteString>> adapter;

    public FeaturesConfigDeserializer(Moshi moshi) {
      this.adapter =
          moshi.adapter(Types.newParameterizedType(Map.class, String.class, ByteString.class));
    }

    @Override
    public FeaturesConfig deserialize(byte[] content) throws IOException {
      Map<String, ByteString> map =
          this.adapter.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(content))));
      return new FeaturesConfig(map);
    }
  }
}

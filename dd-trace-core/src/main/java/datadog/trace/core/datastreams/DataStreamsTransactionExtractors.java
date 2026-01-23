package datadog.trace.core.datastreams;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.List;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataStreamsTransactionExtractors {
  public static final DataStreamsTransactionExtractors EMPTY =
      new DataStreamsTransactionExtractors("[]", Collections.emptyList());
  private static final Logger LOG = LoggerFactory.getLogger(DataStreamsTransactionExtractors.class);
  private static final Moshi MOSHI =
      new Moshi.Builder()
          .add(new DataStreamsTransactionExtractors.DataStreamsTransactionExtractorAdapter())
          .build();
  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, DataStreamsTransactionExtractorImpl.class);
  public static final JsonAdapter<List<DataStreamsTransactionExtractor>> LIST_OF_RULES_ADAPTER =
      MOSHI.adapter(LIST_OF_RULES);

  private final List<DataStreamsTransactionExtractor> extractors;
  private final String json;

  public DataStreamsTransactionExtractors(
      String json, List<DataStreamsTransactionExtractor> extractors) {
    this.extractors = Collections.unmodifiableList(extractors);
    this.json = json;
  }

  public static DataStreamsTransactionExtractors deserialize(String json) {
    try {
      return new DataStreamsTransactionExtractors(json, LIST_OF_RULES_ADAPTER.fromJson(json));
    } catch (Throwable ex) {
      LOG.debug("Couldn't parse Data Streams Extractors from JSON: {}", json, ex);
    }

    return EMPTY;
  }

  public List<DataStreamsTransactionExtractor> getExtractors() {
    return extractors;
  }

  private static final class JsonDataStreamsTransactionExtractor {
    private static final JsonAdapter<JsonDataStreamsTransactionExtractor> jsonAdapter =
        MOSHI.adapter(JsonDataStreamsTransactionExtractor.class);
    String name;
    String type;
    String value;

    @Override
    public String toString() {
      return jsonAdapter.toJson(this);
    }
  }

  public static final class DataStreamsTransactionExtractorsAdapter {
    @FromJson
    DataStreamsTransactionExtractors fromJson(
        JsonReader reader, JsonAdapter<List<DataStreamsTransactionExtractor>> parser)
        throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      try (BufferedSource source = reader.nextSource()) {
        String json = source.readUtf8();
        return new DataStreamsTransactionExtractors(json, parser.fromJson(json));
      }
    }

    @ToJson
    String toJson(DataStreamsTransactionExtractors extractors) {
      return extractors.json;
    }
  }

  public static final class DataStreamsTransactionExtractorAdapter {
    private static DataStreamsTransactionExtractor create(
        JsonDataStreamsTransactionExtractor jsonExtractor) {

      DataStreamsTransactionExtractor.Type type;
      try {
        type = DataStreamsTransactionExtractor.Type.valueOf(jsonExtractor.type);
      } catch (Throwable ex) {
        type = DataStreamsTransactionExtractor.Type.UNKNOWN;
      }

      return new DataStreamsTransactionExtractorImpl(jsonExtractor.name, type, jsonExtractor.value);
    }

    @FromJson
    DataStreamsTransactionExtractor fromJson(JsonDataStreamsTransactionExtractor jsonExtractor) {
      return create(jsonExtractor);
    }

    @ToJson
    JsonDataStreamsTransactionExtractor toJson(DataStreamsTransactionExtractor extractor) {
      throw new UnsupportedOperationException();
    }
  }

  public static final class DataStreamsTransactionExtractorImpl
      implements DataStreamsTransactionExtractor {
    private final String name;
    private final DataStreamsTransactionExtractor.Type type;
    private final String value;

    public DataStreamsTransactionExtractorImpl(
        final String name, final DataStreamsTransactionExtractor.Type type, final String value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public DataStreamsTransactionExtractor.Type getType() {
      return type;
    }

    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return "DataStreamsTransactionExtractorImpl{"
          + "name='"
          + name
          + "', type='"
          + type.name()
          + "', value='"
          + value
          + "'}";
    }
  }
}

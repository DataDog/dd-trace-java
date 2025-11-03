package datadog.trace.core.datastreams;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataStreamsTransactionExtractors {
  public static final DataStreamsTransactionExtractors EMPTY =
      new DataStreamsTransactionExtractors(Collections.emptyList());
  private static final Logger log = LoggerFactory.getLogger(DataStreamsTransactionExtractors.class);
  private static final Moshi MOSHI =
      new Moshi.Builder()
          .add(new DataStreamsTransactionExtractors.DataStreamsTransactionExtractorAdapter())
          .build();
  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, DataStreamsTransactionExtractor.class);
  private static final JsonAdapter<List<DataStreamsTransactionExtractor>> LIST_OF_RULES_ADAPTER =
      MOSHI.adapter(LIST_OF_RULES);

  private final List<DataStreamsTransactionExtractor> extractors;

  public DataStreamsTransactionExtractors(List<DataStreamsTransactionExtractor> extractors) {
    this.extractors = Collections.unmodifiableList(extractors);
  }

  public static DataStreamsTransactionExtractors deserialize(String json) {
    try {
      return new DataStreamsTransactionExtractors(LIST_OF_RULES_ADAPTER.fromJson(json));
    } catch (Throwable ex) {
      log.error("Couldn't parse Data Streams Extractors from JSON: {}", json, ex);
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

  private static final class DataStreamsTransactionExtractorAdapter {
    private static DataStreamsTransactionExtractor create(
        JsonDataStreamsTransactionExtractor jsonExtractor) {
      return new DataStreamsTransactionExtractor(
          jsonExtractor.name, jsonExtractor.type, jsonExtractor.value);
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
}

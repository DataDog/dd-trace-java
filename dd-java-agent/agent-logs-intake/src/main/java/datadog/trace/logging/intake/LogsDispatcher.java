package datadog.trace.logging.intake;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.BackendApi;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogsDispatcher {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogsDispatcher.class);

  private static final MediaType JSON = MediaType.get("application/json");
  private static final IOThrowingFunction<InputStream, Object> IGNORE_RESPONSE = is -> null;

  // Maximum array size if sending multiple logs in an array: 1000 entries
  static final int MAX_BATCH_RECORDS = 1000;

  // Maximum content size per payload (uncompressed): 5MB
  static final int MAX_BATCH_BYTES = 5 * 1024 * 1024;

  // Maximum size for a single log: 1MB
  static final int MAX_MESSAGE_BYTES = 1024 * 1024;

  private final BackendApi backendApi;
  private final JsonAdapter<Map> jsonAdapter;
  private final int maxBatchRecords;
  private final int maxBatchBytes;
  private final int maxMessageBytes;

  public LogsDispatcher(BackendApi backendApi) {
    this(backendApi, MAX_BATCH_RECORDS, MAX_BATCH_BYTES, MAX_MESSAGE_BYTES);
  }

  LogsDispatcher(
      BackendApi backendApi, int maxBatchRecords, int maxBatchBytes, int maxMessageBytes) {
    this.backendApi = backendApi;

    Moshi moshi = new Moshi.Builder().build();
    jsonAdapter = moshi.adapter(Map.class);

    this.maxBatchRecords = maxBatchRecords;
    this.maxBatchBytes = maxBatchBytes;
    this.maxMessageBytes = maxMessageBytes;
  }

  public void dispatch(List<Map<String, Object>> messages) {
    StringBuilder batch = new StringBuilder("[");
    int batchCount = 0, batchLength = 0;

    for (Map<String, Object> message : messages) {
      String json = jsonAdapter.toJson(message);
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > maxMessageBytes) {
        LOGGER.debug("Discarding a log message whose size {} exceeds the limit", bytes.length);
        continue;
      }

      if (batchCount + 1 > maxBatchRecords || batchLength + bytes.length >= maxBatchBytes) {
        flush(batch.append(']'));
        batch = new StringBuilder("[");
        batchCount = 0;
        batchLength = 0;
      }

      if (batchCount != 0) {
        batch.append(',');
      }
      batch.append(json);
      batchCount += 1;
      batchLength += bytes.length;
    }

    flush(batch.append(']'));
  }

  private void flush(StringBuilder batch) {
    try {
      String json = batch.toString();
      RequestBody requestBody = RequestBody.create(JSON, json);
      RequestBody gzippedRequestBody = OkHttpUtils.gzippedRequestBodyOf(requestBody);
      backendApi.post("logs", gzippedRequestBody, IGNORE_RESPONSE, null, true);
    } catch (IOException e) {
      LOGGER.error("Could not dispatch logs", e);
    }
  }
}

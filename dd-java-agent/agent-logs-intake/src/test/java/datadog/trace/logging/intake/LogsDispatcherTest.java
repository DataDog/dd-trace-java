package datadog.trace.logging.intake;

import static datadog.trace.logging.intake.LogsDispatcher.MAX_BATCH_BYTES;
import static datadog.trace.logging.intake.LogsDispatcher.MAX_BATCH_RECORDS;
import static datadog.trace.logging.intake.LogsDispatcher.MAX_MESSAGE_BYTES;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import datadog.communication.util.IOUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class LogsDispatcherTest {

  @Test
  void messagesThatAreTooLargeAreDropped() {
    DummyBackendApi api = new DummyBackendApi();
    int maxMessageBytes = 20;
    LogsDispatcher dispatcher =
        new LogsDispatcher(api, MAX_BATCH_RECORDS, MAX_BATCH_BYTES, maxMessageBytes);

    // when
    dispatcher.dispatch(messages("a", "abcdefghijklmnop"));

    // then
    assertEquals(1, api.messages.size());
    assertEquals("a", api.messages.poll().get("message"));
  }

  @Test
  void batchesThatExceedMaxNumberOfRecordsAreSplitIntoMultipleChunks() {
    DummyBackendApi api = new DummyBackendApi();
    int maxBatchRecords = 2;
    LogsDispatcher dispatcher =
        new LogsDispatcher(api, maxBatchRecords, MAX_BATCH_BYTES, MAX_MESSAGE_BYTES);

    // when
    dispatcher.dispatch(messages("a", "b", "c", "d", "e"));

    // then
    assertEquals(3, api.requestsReceived);
    assertReceivedMessages(api, "a", "b", "c", "d", "e");
  }

  @Test
  void batchesThatExceedMaxNumberOfBytesAreSplitIntoMultipleChunks() {
    DummyBackendApi api = new DummyBackendApi();
    int maxBatchBytes = 40;
    LogsDispatcher dispatcher =
        new LogsDispatcher(api, MAX_BATCH_RECORDS, maxBatchBytes, MAX_MESSAGE_BYTES);

    // when
    dispatcher.dispatch(messages("a", "b", "c", "d", "e"));

    // then
    assertEquals(3, api.requestsReceived);
    assertReceivedMessages(api, "a", "b", "c", "d", "e");
  }

  private static List<Map<String, Object>> messages(String... values) {
    List<Map<String, Object>> messages = new ArrayList<>();
    for (String value : values) {
      messages.add(singletonMap("message", value));
    }
    return messages;
  }

  private static void assertReceivedMessages(DummyBackendApi api, String... expectedMessages) {
    assertEquals(expectedMessages.length, api.messages.size());
    for (String expectedMessage : expectedMessages) {
      assertEquals(expectedMessage, api.messages.poll().get("message"));
    }
  }

  private static final class DummyBackendApi implements BackendApi {
    private final JsonAdapter<List<Map<String, Object>>> listJsonAdapter =
        new Moshi.Builder()
            .build()
            .adapter(
                Types.newParameterizedType(
                    List.class, Types.newParameterizedType(Map.class, String.class, Object.class)));
    private final Queue<Map<String, Object>> messages = new ArrayDeque<>();
    private int requestsReceived = 0;

    @Override
    public <T> T post(
        String uri,
        RequestBody requestBody,
        IOThrowingFunction<InputStream, T> responseParser,
        @Nullable OkHttpUtils.CustomListener requestListener,
        boolean requestCompression)
        throws IOException {
      assertTrue(requestCompression, "Expected request to be compressed");

      requestsReceived++;

      Buffer buffer = new Buffer();
      requestBody.writeTo(buffer);
      try (InputStream inputStream = new GZIPInputStream(buffer.inputStream())) {
        String json = IOUtils.readFully(inputStream);
        messages.addAll(listJsonAdapter.fromJson(json));
      }
      return null;
    }
  }
}

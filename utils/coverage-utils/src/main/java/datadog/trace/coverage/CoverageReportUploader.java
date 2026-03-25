package datadog.trace.coverage;

import static datadog.communication.http.OkHttpUtils.jsonRequestBodyOf;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.http.OkHttpUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.BufferedSink;

public class CoverageReportUploader {

  private final BackendApi backendApi;
  private final Map<String, ?> tags;
  @Nullable private final OkHttpUtils.CustomListener requestListener;
  private final JsonAdapter<Map<String, ?>> eventAdapter;

  public CoverageReportUploader(
      BackendApi backendApi,
      Map<String, ?> tags,
      @Nullable OkHttpUtils.CustomListener requestListener) {
    this.backendApi = backendApi;
    this.tags = tags;
    this.requestListener = requestListener;

    Moshi moshi = new Moshi.Builder().build();
    Type type = Types.newParameterizedType(Map.class, String.class, Object.class);
    eventAdapter = moshi.adapter(type);
  }

  public void upload(String format, InputStream reportStream) throws IOException {
    Map<String, Object> event = new HashMap<>(tags);
    event.put("format", format);
    event.put("type", "coverage_report");
    String eventJson = eventAdapter.toJson(event);
    RequestBody eventBody = jsonRequestBodyOf(eventJson.getBytes(StandardCharsets.UTF_8));

    RequestBody coverageBody = new GzipMultipartRequestBody(reportStream);

    MultipartBody multipartBody =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("coverage", "coverage.gz", coverageBody)
            .addFormDataPart("event", "event.json", eventBody)
            .build();

    backendApi.post("cicovreprt", multipartBody, responseStream -> null, requestListener, false);
  }

  /** Request body that compresses a form data part */
  private static class GzipMultipartRequestBody extends RequestBody {
    private final InputStream stream;

    private GzipMultipartRequestBody(InputStream stream) {
      this.stream = stream;
    }

    @Override
    public long contentLength() {
      return -1;
    }

    @Override
    public MediaType contentType() {
      return null;
    }

    @SuppressFBWarnings("OS_OPEN_STREAM")
    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      GZIPOutputStream outputStream = new GZIPOutputStream(sink.outputStream());
      byte[] buffer = new byte[8192];
      for (int readCount; (readCount = stream.read(buffer)) != -1; ) {
        outputStream.write(buffer, 0, readCount);
      }
      outputStream.finish();
      // not closing output stream as it would close the underlying sink, which is managed by okhttp
    }
  }
}

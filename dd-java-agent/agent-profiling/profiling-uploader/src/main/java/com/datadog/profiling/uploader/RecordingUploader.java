/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.uploader.util.ChunkReader;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** The class for uploading recordings to the backend. */
@Slf4j
public final class RecordingUploader {
  // This logger will be called repeatedly
  static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  static final String RECORDING_NAME_PARAM = "recording-name";
  static final String FORMAT_PARAM = "format";
  static final String TYPE_PARAM = "type";
  static final String RUNTIME_PARAM = "runtime";

  // This is just the requested times. Later we will do this right, with per chunk info.
  // Also this information should not have to be repeated in every request.
  static final String RECORDING_START_PARAM = "recording-start";
  static final String RECORDING_END_PARAM = "recording-end";

  // May want to defined these somewhere where they can be shared in the public API
  static final String CHUNK_SEQUENCE_NUMBER_PARAM = "chunk-seq-num";

  static final String CHUNK_DATA_PARAM = "chunk-data";

  static final String TAGS_PARAM = "tags[]";

  // TODO: Review timeout value to make sure we are not loosing data
  static final Duration HTTP_TIMEOUT =
      Duration.ofSeconds(5); // 5 seconds for connect/read/write operations
  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;

  static final String RECORDING_FORMAT = "jfr";
  static final String RECORDING_TYPE = "jfr";
  static final String RECORDING_RUNTIME = "jvm";

  private static final Headers CHUNK_DATA_HEADERS =
      Headers.of(
          "Content-Disposition",
          "form-data; name=\"" + CHUNK_DATA_PARAM + "\"; filename=\"chunk\"");

  private static final Callback RESPONSE_CALLBACK =
      new Callback() {
        @Override
        public void onFailure(final Call call, final IOException e) {
          log.error("Failed to upload chunk", e);
        }

        @Override
        public void onResponse(final Call call, final Response response) {
          // Apparently we have to do this with okHttp, even if we do not use the body
          if (response.body() != null) {
            response.body().close();
          }
          if (response.isSuccessful()) {
            log.info("Upload done");
          } else {
            log.error("Failed to upload chunk: unexpected response code: " + response);
          }
        }
      };

  private final OkHttpClient client;
  private final String apiKey;
  private final String url;
  private final List<String> tags;

  public RecordingUploader(final String url, final String apiKey, final Map<String, String> tags) {
    this.url = url;
    this.apiKey = apiKey;
    this.tags = tagsToList(tags);

    client =
        new OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT)
            .writeTimeout(HTTP_TIMEOUT)
            .readTimeout(HTTP_TIMEOUT)
            .callTimeout(HTTP_TIMEOUT)
            .build();

    client.dispatcher().setMaxRequests(MAX_RUNNING_REQUESTS);
    // We are mainly talking to the same(ish) host so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);
  }

  public void upload(final RecordingData data) {
    try {
      if (canEnqueueMoreRequests()) {
        final Iterator<byte[]> chunkIterator = ChunkReader.readChunks(data.getStream());
        int chunkCounter = 0;
        while (chunkIterator.hasNext()) {
          uploadChunk(data, chunkCounter++, chunkIterator.next());
        }
      } else {
        log.error("Cannot upload data: too many enqueued requests!");
      }
    } catch (final IllegalStateException | IOException e) {
      log.error("Problem uploading recording chunk!", e);
    } finally {
      // Chunk loader closes stream automatically - only need to release RecordingData
      data.release();
    }
  }

  public void shutdown() {
    client.dispatcher().executorService().shutdown();
  }

  private void uploadChunk(final RecordingData data, final int chunkId, final byte[] chunk) {
    log.info("Uploading chunk {} [{}] (Size={} bytes)", data.getName(), chunkId, chunk.length);

    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(RECORDING_NAME_PARAM, data.getName())
            .addFormDataPart(FORMAT_PARAM, RECORDING_FORMAT)
            .addFormDataPart(TYPE_PARAM, RECORDING_TYPE)
            .addFormDataPart(RUNTIME_PARAM, RECORDING_RUNTIME)
            // Note that toString is well defined for instants - ISO-8601
            .addFormDataPart(RECORDING_START_PARAM, data.getStart().toString())
            .addFormDataPart(RECORDING_END_PARAM, data.getEnd().toString())
            .addFormDataPart(CHUNK_SEQUENCE_NUMBER_PARAM, String.valueOf(chunkId));
    for (final String tag : tags) {
      bodyBuilder.addFormDataPart(TAGS_PARAM, tag);
    }
    bodyBuilder.addPart(CHUNK_DATA_HEADERS, RequestBody.create(OCTET_STREAM, chunk));
    final RequestBody requestBody = bodyBuilder.build();

    final Request request =
        new Request.Builder()
            .url(url)
            .addHeader("Authorization", Credentials.basic(apiKey, ""))
            // Note: this header is also used to disable tracing of profiling requests
            .addHeader(VersionInfo.DATADOG_META_LANG, VersionInfo.JAVA_LANG)
            .addHeader(VersionInfo.DATADOG_META_LANG_VERSION, VersionInfo.JAVA_VERSION)
            .addHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER, VersionInfo.JAVA_VM_NAME)
            .addHeader(VersionInfo.DATADOG_META_TRACER_VERSION, VersionInfo.VERSION)
            .post(requestBody)
            .build();

    client.newCall(request).enqueue(RESPONSE_CALLBACK);
  }

  private boolean canEnqueueMoreRequests() {
    // This is a soft limit since recording data may contain many chunks which are
    // uploaded with multiple requests.
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet()
        .stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }
}

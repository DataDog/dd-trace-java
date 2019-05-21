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
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The class for uploading recordings somewhere. This is what eventually will call our edge service.
 */
@Slf4j
final class UploadingTask implements Runnable {
  // This logger will be called repeatedly
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  // May want to defined these somewhere where they can be shared in the public API
  static final String KEY_CHUNK_SEQ_NO = "chunk-seq-num";
  static final String KEY_RECORDING_NAME = "recording-name";
  // This is just the requested times. Later we will do this right, with per chunk info.
  // Also this information should not have to be repeated in every request.
  static final String KEY_RECORDING_START = "recording-start";
  static final String KEY_RECORDING_END = "recording-end";
  static final String KEY_TAG = "tags[]";

  private static final OkHttpClient CLIENT = new OkHttpClient();
  private final RecordingData data;
  private final String apiKey;
  private final String url;
  private final String[] tags;

  public UploadingTask(
      final String url, final String apiKey, final String[] tags, final RecordingData data) {
    this.url = url;
    this.apiKey = apiKey;
    this.tags = tags;
    this.data = data;
  }

  @Override
  public void run() {
    try {
      final Iterator<byte[]> chunkIterator = ChunkReader.readChunks(data.getStream());
      int chunkCounter = 0;
      while (chunkIterator.hasNext()) {
        uploadChunk(data, chunkCounter++, chunkIterator.next());
      }
      // Chunk loader closes stream automatically - only need to release RecordingData
      data.release();
    } catch (final IllegalStateException | IOException e) {
      log.error("Problem uploading recording chunk!", e);
    }
  }

  private void uploadChunk(final RecordingData data, final int chunkId, final byte[] chunk)
      throws IOException {
    log.info("Uploading {} [{}] (Size={} bytes)", data.getName(), chunkId, chunk.length);

    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(KEY_RECORDING_NAME, data.getName())
            // Note that toString is well defined for instants - ISO-8601
            .addFormDataPart(KEY_RECORDING_START, data.getRequestedStart().toString())
            .addFormDataPart(KEY_RECORDING_END, data.getRequestedEnd().toString())
            .addFormDataPart(KEY_CHUNK_SEQ_NO, String.valueOf(chunkId));
    for (final String tag : tags) {
      bodyBuilder.addFormDataPart(KEY_TAG, tag);
    }
    bodyBuilder.addPart(
        Headers.of("Content-Disposition", "form-data; name=\"jfr-chunk-data\"; filename=\"chunk\""),
        RequestBody.create(OCTET_STREAM, chunk));
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

    final Response response = CLIENT.newCall(request).execute();
    // Apparently we have to do this with okHttp, even if we do not use the body
    if (response.body() != null) {
      response.body().close();
    }
    if (response.isSuccessful()) {
      log.info("Upload done");
    } else {
      throw new IOException("Unexpected code " + response);
    }
  }
}

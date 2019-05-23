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

import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingDataListener;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Code for uploading whatever recording data captured to Datadog. Create this class before the
 * {@link ProfilingSystem} and hand the {@link RecordingDataListener} to the constructor of the
 * {@link ProfilingSystem}.
 *
 * <p>Don't forget to shut down this component when no longer needed.
 */
@Slf4j
public final class Uploader {

  // Allows upload of 1 continuous and one profiling recording simultaneously. Of course, spamming
  // dumps of the continuous recording may get us in trouble, so we will likely protect against that
  // at some point. For the normal use case, it's expected that one of these upload threads will
  // mostly be idle.
  private final ExecutorService uploadingTaskExecutor =
      Executors.newFixedThreadPool(2, new ProfilingUploaderThreadFactory());
  private final RecordingUploader recordingUploader;

  private final RecordingDataListener listener = new ProfilingDataCallback();

  private final class ProfilingDataCallback implements RecordingDataListener {
    /** Just handing this off to the uploading threads. */
    @Override
    public void onNewData(final RecordingData data) {
      uploadingTaskExecutor.execute(new UploadingTask(recordingUploader, data));
    }
  }

  /**
   * Constructor.
   *
   * @param url the URL of the edge service.
   * @param apiKey the apiKey to use.
   * @param tags the tags to use.
   */
  public Uploader(final String url, final String apiKey, final Map<String, String> tags) {
    this(new RecordingUploader(url, apiKey, tags));
  }

  Uploader(final RecordingUploader recordingUploader) {
    this.recordingUploader = recordingUploader;
  }

  public RecordingDataListener getRecordingDataListener() {
    return listener;
  }

  public void shutdown() {
    uploadingTaskExecutor.shutdown();
    try {
      uploadingTaskExecutor.awaitTermination(10, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      log.error("Wait for executor shutdown interrupted");
    }
  }
}

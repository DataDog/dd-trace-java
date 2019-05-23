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

/**
 * The class for uploading recordings somewhere. This is what eventually will call our edge service.
 */
final class UploadingTask implements Runnable {

  private final RecordingUploader uploader;
  private final RecordingData data;

  public UploadingTask(final RecordingUploader uploader, final RecordingData data) {
    this.uploader = uploader;
    this.data = data;
  }

  @Override
  public void run() {
    uploader.upload(data);
  }
}

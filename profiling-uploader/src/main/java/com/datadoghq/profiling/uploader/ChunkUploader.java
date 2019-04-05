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
package com.datadoghq.profiling.uploader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datadoghq.profiling.controller.ProfilingSystem;
import com.datadoghq.profiling.controller.RecordingData;
import com.datadoghq.profiling.controller.RecordingDataListener;

/**
 * Code for uploading whatever recording data captured to Datadog. Create this class before the
 * {@link ProfilingSystem} and hand the {@link RecordingDataListener} to the constructor of the
 * {@link ProfilingSystem}.
 * <p>
 * Don't forget to shut down this component when no longer needed.
 * 
 * @author Marcus Hirt
 */
public final class ChunkUploader {
	// Allows upload of 1 continuous and one profiling recording simultaneously. Of course, spamming 
	// dumps of the continuous recording may get us in trouble, so we will likely protect against that 
	// at some point. For the normal use case, it's expected that one of these upload threads will mostly be idle.
	private final ExecutorService uploadingTaskExecutor = Executors.newFixedThreadPool(2,
			new ProfilingUploaderThreadFactory());

	private final RecordingDataListener listener = new ProfilingDataCallback();

	private final String url;
	private final String apiKey;

	private final class ProfilingDataCallback implements RecordingDataListener {
		/**
		 * Just handing this off to the uploading threads.
		 */
		public void onNewData(RecordingData data) {
			uploadingTaskExecutor.execute(new UploadingTask(url, apiKey, data));
		}
	}

	public ChunkUploader(String url, String apiKey) {
		this.url = url;
		this.apiKey = apiKey;
	}

	public RecordingDataListener getRecordingDataListener() {
		return listener;
	}

	public void shutdown() {
		if (uploadingTaskExecutor != null) {
			uploadingTaskExecutor.shutdown();
			// May want to await termination here for a tad.
		}
	}
}

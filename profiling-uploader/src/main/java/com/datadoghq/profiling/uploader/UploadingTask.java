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

import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.datadoghq.profiling.controller.RecordingData;
import com.datadoghq.profiling.uploader.util.ChunkReader;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.MultipartBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

/**
 * The class for uploading recordings somewhere. This is what eventually will call our edge service.
 */
final class UploadingTask implements Runnable {
	// This logger will be called repeatedly
	private final static Logger LOGGER = Logger.getLogger(UploadingTask.class.getName());
	private final static MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

	// May want to defined these somewhere where they can be shared in the public API
	static final String KEY_CHUNK_SEQ_NO = "chunk-seq-num";
	static final String KEY_RECORDING_NAME = "recording-name";
	// This is just the requested times. Later we will do this right, with per chunk info.
	// Also this information should not have to be repeated in every request.
	static final String KEY_RECORDING_START = "recording-start";
	static final String KEY_RECORDING_END = "recording-end";
	static final String HEADER_KEY_APIKEY = "apikey";

	private final static OkHttpClient CLIENT = new OkHttpClient();
	private final RecordingData data;
	private final String apiKey;
	private final String url;
	private final String credentials;

	public UploadingTask(String url, String apiKey, String credentials, RecordingData data) {
		this.url = url;
		this.apiKey = apiKey;
		this.credentials = credentials;
		this.data = data;
	}

	@Override
	public void run() {
		try {
			Iterator<byte[]> chunkIterator = ChunkReader.readChunks(data.getStream());
			int chunkCounter = 0;
			while (chunkIterator.hasNext()) {
				uploadChunk(data, chunkCounter++, chunkIterator.next());
			}
			// Chunk loader closes stream automatically - only need to release RecordingData
			data.release();
		} catch (IllegalStateException | IOException e) {
			LOGGER.log(Level.SEVERE, "Problem uploading recording chunk!", e);
		}
	}

	private void uploadChunk(RecordingData data, int chunkId, byte[] chunk) throws IOException {
		LOGGER.log(Level.INFO,
				"Beginning upload of " + data.getName() + "[" + chunkId + "] (Size=" + chunk.length + " bytes)");

		RequestBody requestBody = new MultipartBuilder().type(MultipartBuilder.FORM)
				.addFormDataPart(KEY_RECORDING_NAME, data.getName())
				// Note that toString is well defined for instants - ISO-8601
				.addFormDataPart(KEY_RECORDING_START, data.getRequestedStart().toString())
				.addFormDataPart(KEY_RECORDING_END, data.getRequestedEnd().toString())
				.addFormDataPart(KEY_CHUNK_SEQ_NO, String.valueOf(chunkId))
				.addPart(Headers.of("Content-Disposition", "form-data; name=\"jfr-chunk-data\"; filename=\"chunk\""),
						RequestBody.create(OCTET_STREAM, chunk))
				.build();

		Request request = new Request.Builder().header(HEADER_KEY_APIKEY, apiKey)
				.addHeader("Authorization", credentials).url(url).post(requestBody).build();

		Response response = CLIENT.newCall(request).execute();
		if (!response.isSuccessful()) {
			throw new IOException("Unexpected code " + response);
		}
	}
}

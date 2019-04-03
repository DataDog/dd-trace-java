package com.datadoghq.profiling.uploader;

import java.io.IOException;
import java.time.Duration;

import org.junit.Test;

import com.datadoghq.profiling.controller.ProfilingSystem;
import com.datadoghq.profiling.controller.UnsupportedEnvironmentException;

/**
 * Unit test for simple App.
 */
public class ChunkUploaderTest {
	@Test
	public void testUploader() throws IOException, UnsupportedEnvironmentException, InterruptedException {
		ChunkUploader uploader = new ChunkUploader();
		ProfilingSystem system = new ProfilingSystem(uploader.getRecordingDataListener(), Duration.ZERO,
				Duration.ofSeconds(5), Duration.ofSeconds(2));
		system.start();

		// Silly test - we'll do better when we do actual work later. Just wait and see if something breaks.
		Thread.sleep(10000);
		system.shutdown();
		uploader.shutdown();
	}

	public static void main(String[] args) throws IOException, UnsupportedEnvironmentException, InterruptedException {
		new ChunkUploaderTest().testUploader();
	}
}

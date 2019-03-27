package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit test for simple App.
 */
public class ProfilingSystemTest {
	public static void main(String[] args) throws Exception {
		new ProfilingSystemTest().testProfilingSystem();
	}

	/**
	 * Ensuring that it can be run and destroyed without any problems.
	 */
	@Test
	public void testProfilingSystemInit() throws UnsupportedEnvironmentException, IOException {
		ProfilingSystem system = new ProfilingSystem();
		system.initialize();
	}

	/**
	 * Ensuring that it can be run and started. Will wait for a bit to get the initial recording
	 * done.
	 * 
	 * @throws InterruptedException
	 */
	@Test
	public void testProfilingSystem() throws UnsupportedEnvironmentException, IOException, InterruptedException {
		ProfilingSystem system = new ProfilingSystem();
		system.initialize(Duration.ofMillis(0), Duration.ofSeconds(5), Duration.ofSeconds(2));

		final Path recordingFolder = system.getRecordingFolder();

		WatchService watchService = recordingFolder.getFileSystem().newWatchService();
		WatchKey watchKey = recordingFolder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
		// Give it a maximum of 10 seconds to write something...
		watchService.poll(10, TimeUnit.SECONDS);
		watchKey.cancel();
		watchService.close();
		system.shutdown();
		String[] files = recordingFolder.toFile().list();
		Assert.assertTrue(files.length > 0);
	}
}

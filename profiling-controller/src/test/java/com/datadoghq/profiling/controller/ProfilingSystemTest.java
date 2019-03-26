package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for simple App.
 */
public class ProfilingSystemTest extends TestCase {
	/**
	 * Create the test case
	 *
	 * @param testName
	 *            name of the test case
	 */
	public ProfilingSystemTest(String testName) {
		super(testName);
	}

	/**
	 * @return the suite of tests being tested
	 */
	public static Test suite() {
		return new TestSuite(ProfilingSystemTest.class);
	}

	/**
	 * Ensuring that it can be run and destroyed without any problems.
	 */
	public void testProfilingSystemInit() throws UnsupportedEnvironmentException, IOException {
		ProfilingSystem system = new ProfilingSystem();
		system.initialize();
	}
	
	/**
	 * Ensuring that it can be run and started. Will wait for a bit to get the initial recording done.
	 * @throws InterruptedException 
	 */
	public void testProfilingSystem() throws UnsupportedEnvironmentException, IOException, InterruptedException {
		ProfilingSystem system = new ProfilingSystem();
		system.initialize(Duration.ofMillis(0), Duration.ofSeconds(5), Duration.ofSeconds(2));
		
		final Path recordingFolder = system.getRecordingFolder();
		
		WatchService watcher = new TestFileSystemWatcher();
		WatchKey watchKey = recordingFolder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
		
		watcher.poll(10, TimeUnit.SECONDS);
		watchKey.cancel();
		system.shutdown();
		String [] files = recordingFolder.toFile().list();
		assertTrue(files.length > 0);
	}

}

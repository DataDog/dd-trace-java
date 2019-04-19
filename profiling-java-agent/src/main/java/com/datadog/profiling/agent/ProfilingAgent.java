package com.datadog.profiling.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datadog.profiling.controller.BadConfigurationException;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ChunkUploader;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line, without requiring
 * the APM agent. This makes it possible to run the profiling agent stand-alone. Of course, this
 * also means no contextual events from the tracing will be present.
 */
public class ProfilingAgent {
	private static final String ENV_VAR_DURATION = "DD_PROFILE_DURATION_SEC";
	private static final String ENV_VAR_PERIOD = "DD_PROFILE_PERIOD_SEC";
	private static final String ENV_VAR_DELAY = "DD_PROFILE_DELAY_SEC";
	private static final String ENV_VAR_URL = "DD_PROFILE_ENDPOINT";
	private static final String ENV_VAR_API_KEY = "DD_PROFILE_API_KEY";

	private static final int DEFAULT_DURATION = 60;
	private static final int DEFAULT_PERIOD = 3600;
	private static final int DEFAULT_DELAY = 30;
	private static final String DEFAULT_URL = "http://localhost:5000/api/v0/profiling/jfk-chunk"; // TODO our eventual prod endpoint

	// Overkill to make these volatile?
	private static ProfilingSystem profiler;
	private static ChunkUploader uploader;

	/**
	 * Called when starting from the command line.
	 */
	public static void premain(String args, Instrumentation instrumentation) {
		initialize();
	}

	/**
	 * Called when loaded and run from attach. If the agent is already initialized (from either the
	 * command line, or dynamically loaded through attach, no action will be taken.
	 */
	public static void agentmain(String args, Instrumentation instrumentation) {
		initialize();
	}

	private static synchronized void initialize() throws IllegalArgumentException {
		if (profiler == null) {
			String apiKey = System.getenv(ENV_VAR_API_KEY);
			if (apiKey == null) {
				throw new IllegalArgumentException("You must set env var " + ENV_VAR_API_KEY);
			}
			uploader = new ChunkUploader(getString(ENV_VAR_URL, DEFAULT_URL), apiKey);
			try {
				profiler = new ProfilingSystem(uploader.getRecordingDataListener(),
						Duration.ofSeconds(getInt(ENV_VAR_DELAY, DEFAULT_DELAY)),
						Duration.ofSeconds(getInt(ENV_VAR_PERIOD, DEFAULT_PERIOD)),
						Duration.ofSeconds(getInt(ENV_VAR_DURATION, DEFAULT_DURATION)));
				profiler.start();
			} catch (UnsupportedEnvironmentException | IOException | BadConfigurationException e) {
				getLogger().warn("Failed to initialize profiling agent!", e);
			}
		}
	}

	private static int getInt(String key, int defaultValue) {
		String val = System.getenv(key);
		if (val != null) {
			try {
				return Integer.valueOf(val);
			} catch (NumberFormatException e) {
				getLogger().warn("Could not parse key {}. Will go with default {}.", key, defaultValue);
				return defaultValue;
			}
		}
		getLogger().info("Could not find key {}. Will go with default {}.", key, defaultValue);
		return defaultValue;
	}

	private static String getString(String key, String defaultValue) {
		String val = System.getenv(key);
		if (val == null) {
			getLogger().info("Could not find key {}. Will go with default {}.", key, defaultValue);
			return defaultValue;
		}
		return val;
	}

	private static Logger getLogger() {
		return LoggerFactory.getLogger(ProfilingAgent.class);
	}
}

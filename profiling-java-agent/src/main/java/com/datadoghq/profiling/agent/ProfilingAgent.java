package com.datadoghq.profiling.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.datadoghq.profiling.controller.ProfilingSystem;
import com.datadoghq.profiling.controller.UnsupportedEnvironmentException;
import com.datadoghq.profiling.uploader.ChunkUploader;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line, without requiring
 * the APM agent. This makes it possible to run the profiling agent stand-alone. Of course, this
 * also means no contextual events from the tracing will be present.
 * 
 * @author Marcus Hirt
 */
public class ProfilingAgent {
	private static final int DEFAULT_DURATION = 60;
	private static final int DEFAULT_PERIOD = 3600;
	private static final int DEFAULT_DELAY = 30;
	private static final String KEY_DURATION = "duration";
	private static final String KEY_PERIOD = "period";
	private static final String KEY_DELAY = "delay";

	private final static String DEFAULT_PROPERTIES = "profiling.properties";

	// Do these need to be volatile?
	private ProfilingSystem profiler;
	private ChunkUploader uploader;

	/**
	 * Called when starting from the command line.
	 */
	public void premain(String args, Instrumentation instrumentation) {
		Properties props = initProperties(args);
		initialize(Duration.ofSeconds(getInt(props, KEY_DELAY, DEFAULT_DELAY)),
				Duration.ofSeconds(getInt(props, KEY_PERIOD, DEFAULT_PERIOD)),
				Duration.ofSeconds(getInt(props, KEY_DURATION, DEFAULT_DURATION)));
	}

	/**
	 * Called when loaded and run from attach. If the agent is already initialized (from either the
	 * command line, or dynamically loaded through attach, no action will be taken.
	 */
	public void agentmain(String args, Instrumentation instrumentation) {
		Properties props = initProperties(args);
		initialize(Duration.ofSeconds(getInt(props, KEY_DELAY, DEFAULT_DELAY)),
				Duration.ofSeconds(getInt(props, KEY_PERIOD, DEFAULT_PERIOD)),
				Duration.ofSeconds(getInt(props, KEY_DURATION, DEFAULT_DURATION)));
	}

	private synchronized void initialize(Duration delay, Duration period, Duration recordingDuration) {
		if (profiler == null) {
			uploader = new ChunkUploader();
			try {
				// TODO: This is just debug code right now. Proper uploads will probably happen in another module,
				// and then we can use that here. Also, add configuration options (yaml?) etc.
				profiler = new ProfilingSystem(uploader.getRecordingDataListener());
				profiler.start();
			} catch (UnsupportedEnvironmentException | IOException e) {
				getLogger().log(Level.WARNING, "Failed to initialize profiling agent!", e);
			}
		}
	}

	private static Properties initProperties(String args) {
		Properties props = new Properties();
		if (args == null || args.isBlank()) {
			loadDefaultProperties(props);
		} else {
			File propsFile = new File(args);
			if (!propsFile.exists()) {
				getLogger().log(Level.WARNING,
						"The agent settings file " + args + " could not be found! Will go with the defaults!");
				loadDefaultProperties(props);
			} else {
				try (FileInputStream in = new FileInputStream(propsFile)) {
					props.load(in);
				} catch (Exception e) {
					getLogger().log(Level.WARNING, "Failed to load agent settings from " + args
							+ ". File format error? Going with the defaults.");
					loadDefaultProperties(props);
				}
			}
		}
		return props;
	}

	private static void loadDefaultProperties(Properties props) {
		try {
			props.load(ProfilingAgent.class.getClassLoader().getResourceAsStream(DEFAULT_PROPERTIES));
		} catch (IOException e) {
			// Should never happen! Build fail!
			getLogger().log(Level.SEVERE, "Failure to load default properties!", e);
		}
	}

	private static int getInt(Properties props, String key, int defaultValue) {
		String val = props.getProperty(key);
		if (val != null) {
			try {
				return Integer.valueOf(val);
			} catch (NumberFormatException e) {
				getLogger().log(Level.WARNING,
						"Could not parse key " + key + ". Will go with default " + defaultValue + ".");
				return defaultValue;
			}
		}
		getLogger().log(Level.INFO, "Could not find key " + key + ". Will go with default " + defaultValue + ".");
		return defaultValue;
	}

	private static Logger getLogger() {
		return Logger.getLogger(ProfilingAgent.class.getName());
	}
}

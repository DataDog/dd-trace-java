package com.datadoghq.profiling.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.datadoghq.profiling.controller.ProfilingSystem;
import com.datadoghq.profiling.controller.UnsupportedEnvironmentException;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line, without requiring
 * the APM agent. This makes it possible to run the profiling agent stand-alone. Of course, this
 * also means no contextual events from the tracing will be present.
 * 
 * @author Marcus Hirt
 */
public class ProfilingAgent {
	private static final ProfilingSystem profiler = new ProfilingSystem();

	/**
	 * Called when starting from the command line.
	 */
	public void premain(String args, Instrumentation instrumentation) {
		initialize(Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(1));
	}

	/**
	 * Called when loaded and run from attach. Note that this is also fully supported.
	 */
	public void agentmain(String args, Instrumentation instrumentation) {
		initialize(Duration.ofSeconds(0), Duration.ofHours(1), Duration.ofMinutes(1));
	}

	private void initialize(Duration delay, Duration period, Duration recordingDuration) {
		try {
			profiler.initialize(delay, period, recordingDuration);
		} catch (UnsupportedEnvironmentException | IOException e) {
			Logger.getLogger(ProfilingAgent.class.getName()).log(Level.WARNING, "Failed to initialize profiling agent!",
					e);
		}
	}
}

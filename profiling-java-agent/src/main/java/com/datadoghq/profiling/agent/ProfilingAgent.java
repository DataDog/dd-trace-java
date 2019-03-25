package com.datadoghq.profiling.agent;

import java.lang.instrument.Instrumentation;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line,
 * without requiring the APM agent. This makes it possible to run the profiling
 * agent stand-alone. Of course, this also means no contextual events from the
 * tracing will be present.
 * 
 * @author Marcus Hirt
 */
public class ProfilingAgent {

	/**
	 * Called when starting from the command line.
	 */
	public void premain(String args, Instrumentation instrumentation) {

	}

	/**
	 * Called when loaded and run from attach. Note that this is also fully
	 * supported.
	 */
	public void agentmain(String args, Instrumentation instrumentation) {

	}
}

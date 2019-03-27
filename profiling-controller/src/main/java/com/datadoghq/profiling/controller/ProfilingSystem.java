package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import com.datadoghq.profiling.controller.util.JfpUtils;
import com.datadoghq.profiling.scheduler.ProfilingRecordingScheduler;

import jdk.jfr.Recording;

/**
 * Sets up the profiling strategy and schedules the profiling recordings.
 * 
 * @author Marcus Hirt
 */
public final class ProfilingSystem {
	public final static ThreadGroup THREAD_GROUP = new ThreadGroup("Datadog Profiler");

	private final static String JFP_CONTINUOUS = "jfr2/ddcontinuous.jfp";
	private final static String JFP_PROFILE = "jfr2/ddprofile.jfp";

	private ScheduledFuture<?> scheduledFuture;
	private Recording continuousRecording;
	private ProfilingRecordingScheduler scheduler;

	/**
	 * Starts up the profiling system, using the defaults.
	 * 
	 * @throws UnsupportedEnvironmentException
	 *             if the profiling system cannot be run on this system.
	 * @throws IOException
	 *             if there was a problem reading configuration files etc.
	 */
	public final void initialize() throws UnsupportedEnvironmentException, IOException {
		initialize(Duration.ofSeconds(20), Duration.ofHours(1), Duration.ofMinutes(1));
	}

	/**
	 * Starts up the profiling system.
	 * 
	 * @throws UnsupportedEnvironmentException
	 *             if the profiling system cannot be run on this system.
	 * @throws IOException
	 *             if there was a problem reading configuration files etc.
	 */
	public final void initialize(Duration delay, Duration period, Duration recordingDuration)
			throws UnsupportedEnvironmentException, IOException {
		Controller ctrl = ControllerFactory.createController();
		continuousRecording = ctrl.createContinuousRecording("dd_profiler_continuous",
				JfpUtils.readNamedJfpResource(JFP_CONTINUOUS));
		scheduler = new ProfilingRecordingScheduler(ctrl, JfpUtils.readNamedJfpResource(JFP_PROFILE), delay, period,
				recordingDuration);
		scheduledFuture = scheduler.start();
	}

	/**
	 * Shuts down the profiling system.
	 */
	public final void shutdown() {
		continuousRecording.stop();
		scheduledFuture.cancel(true);
	}

	public final Path getRecordingFolder() {
		return scheduler.getRecordingFolder();
	}
}

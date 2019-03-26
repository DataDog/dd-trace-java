package com.datadoghq.profiling.scheduler;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.datadoghq.profiling.controller.Controller;

import jdk.jfr.Recording;

/**
 * The scheduler used to configure and schedule the profiling dumps.
 * 
 * @author Marcus Hirt
 */
public final class ProfilingRecordingScheduler {
	private final static AtomicInteger RECORDING_SEQUENCE_NUMBER = new AtomicInteger();
	private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1,
			new ProfilingRecorderThreadFactory());

	private final RecordingCreator recordingCreator;
	private final long delay;
	private final long period;

	private static class RecordingCreator implements Runnable {
		private final Path targetFolder;
		private final Controller controller;
		private final Map<String, String> template;
		private final Duration recordingDuration;
		private volatile Recording ongoingRecording;

		public RecordingCreator(Controller controller, Map<String, String> template, Duration recordingDuration,
				Path targetFolder) {
			this.controller = controller;
			this.template = template;
			this.recordingDuration = recordingDuration;
			this.targetFolder = targetFolder;
		}

		@Override
		public void run() {
			try {
				ongoingRecording = controller.createRecording("dd-profiling", template, generatePath(),
						recordingDuration);
			} catch (IOException e) {
				Logger.getLogger(ProfilingRecordingScheduler.class.getName()).log(Level.WARNING,
						"Failed to create a recording!", e);
			}
		}

		private Path generatePath() {
			return FileSystems.getDefault().getPath(targetFolder.toFile().getAbsolutePath(),
					"dd-profiling-recording-" + RECORDING_SEQUENCE_NUMBER.getAndIncrement() + ".jfr");
		}
	}

	/**
	 * Creates a profiling recording schedule. Will schedule a recording with the specified settings
	 * every once in a while.
	 * 
	 * @param template
	 *            the event settings to use for the recording.
	 * @param delay
	 *            the initial delay to wait before scheduling the first recording.
	 * @param period
	 *            the periodicity with which to run the recordings.
	 * @param recordingDuration
	 *            the duration for which to record.
	 * @throws IOException
	 *             if something went wrong, for example the creation of the temporary storage
	 *             directory failed.
	 */
	public ProfilingRecordingScheduler(Controller controller, Map<String, String> template, Duration delay,
			Duration period, Duration recordingDuration) throws IOException {
		if (period.minus(recordingDuration).isNegative()) {
			throw new IllegalArgumentException("The recording interval must be greater than the recording durations!");
		}
		Path targetFolder = Files.createTempDirectory("dd-profiling-recordings-", new FileAttribute<?>[0]);
		recordingCreator = new RecordingCreator(controller, template, recordingDuration, targetFolder);
		this.delay = delay.toMillis();
		this.period = period.toMillis();
	}

	/**
	 * Schedules the repeated recording of profiling recordings.
	 */
	public ScheduledFuture<?> start() {
		return executorService.scheduleAtFixedRate(recordingCreator, delay, period, TimeUnit.MILLISECONDS);
	}

	public Path getRecordingFolder() {
		return recordingCreator.targetFolder;
	}

	public void shutdown() {
		executorService.shutdownNow();
		Recording ongoing = recordingCreator.ongoingRecording;
		if (ongoing != null) {
			ongoing.close();
		}
	}
}

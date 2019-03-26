package com.datadoghq.profiling.scheduler;

import java.util.concurrent.ThreadFactory;

import com.datadoghq.profiling.controller.ProfilingSystem;

/**
 * Thread factory for the recording scheduler.
 *
 * @author Marcus Hirt
 */
final class ProfilingRecorderThreadFactory implements ThreadFactory {
	
	@Override
	public Thread newThread(Runnable r) {
		Thread t = new Thread(ProfilingSystem.THREAD_GROUP, r, "Recoding Scheduler");
		t.setDaemon(true);
		return t;
	}
}

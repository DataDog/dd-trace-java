package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

public class TestFileSystemWatcher implements WatchService {

	@Override
	public void close() throws IOException {
	}

	@Override
	public WatchKey poll() {
		return null;
	}

	@Override
	public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
		return null;
	}

	@Override
	public WatchKey take() throws InterruptedException {
		return null;
	}

}

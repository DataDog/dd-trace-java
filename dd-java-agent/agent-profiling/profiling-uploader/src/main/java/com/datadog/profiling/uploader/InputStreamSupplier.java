package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
interface InputStreamSupplier {
  InputStream get() throws IOException;
}

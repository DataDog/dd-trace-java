package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.InputStream;

/** A simple functional supplier throwing an {@linkplain IOException} */
@FunctionalInterface
interface InputStreamSupplier {
  InputStream get() throws IOException;
}

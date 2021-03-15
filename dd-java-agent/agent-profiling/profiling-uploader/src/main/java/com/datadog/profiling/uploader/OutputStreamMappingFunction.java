package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.OutputStream;

/** A simple functional mapper allowing to throw {@linkplain IOException} */
@FunctionalInterface
interface OutputStreamMappingFunction {
  OutputStream apply(OutputStream param) throws IOException;
}

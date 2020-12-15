package com.datadog.profiling.uploader;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
interface OutputStreamMappingFunction {
  OutputStream apply(OutputStream param) throws IOException;
}

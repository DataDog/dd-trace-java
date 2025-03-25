package com.datadog.debugger.symbol;

import java.io.IOException;

public interface SymDBReport {

  void addMissingJar(String jarPath);

  void addIOException(String jarPath, IOException e);

  void addLocationError(String locationStr);

  void incClassCount(String jarPath);

  void addScannedJar(String jarPath);

  void report();

  SymDBReport NO_OP =
      new SymDBReport() {
        @Override
        public void addMissingJar(String jarPath) {}

        @Override
        public void addIOException(String jarPath, IOException e) {}

        @Override
        public void addLocationError(String locationStr) {}

        @Override
        public void incClassCount(String jarPath) {}

        @Override
        public void addScannedJar(String jarPath) {}

        @Override
        public void report() {}
      };
}

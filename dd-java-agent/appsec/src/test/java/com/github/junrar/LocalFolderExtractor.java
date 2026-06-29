package com.github.junrar;

import com.github.junrar.rarfile.FileHeader;
import java.io.File;

// Minimal stub replacing junrar:7.5.5 to avoid shipping a test dependency with known CVEs.
// Preserves the constructor and method signatures used by ScaRealLibraryBytecodeTest.
class LocalFolderExtractor {

  @SuppressWarnings("unused")
  private final File destinationFolder;

  LocalFolderExtractor(File destinationFolder) {
    this.destinationFolder = destinationFolder;
  }

  void createDirectory(FileHeader header) {
    // stub — the test verifies that the injected callback fires at method entry before this body
  }
}

package org.apache.coyote.http11.filters;

// Minimal stub replacing tomcat-embed-core:9.0.115 to avoid shipping a test dependency with known
// CVEs.
// Preserves the method signature used by ScaRealLibraryBytecodeTest.
public class ChunkedInputFilter {

  public void parseChunkHeader() {}
}

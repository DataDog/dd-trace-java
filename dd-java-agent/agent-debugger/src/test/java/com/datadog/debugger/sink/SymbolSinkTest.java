package com.datadog.debugger.sink;

import static com.datadog.debugger.sink.SymbolSink.MAX_SYMDB_UPLOAD_SIZE;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.symbol.Scope;
import com.datadog.debugger.symbol.ScopeType;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.MediaType;
import org.junit.jupiter.api.Test;

class SymbolSinkTest {

  @Test
  public void testSimpleFlush() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, MAX_SYMDB_UPLOAD_SIZE);
    symbolSink.addScope(Scope.builder(ScopeType.JAR, null, 0, 0).build());
    symbolSink.flush();
    assertEquals(2, symbolUploaderMock.multiPartContents.size());
    BatchUploader.MultiPartContent eventContent = symbolUploaderMock.multiPartContents.get(0);
    assertEquals("event", eventContent.getPartName());
    assertEquals("event.json", eventContent.getFileName());
    String strEventContent = new String(eventContent.getContent());
    assertTrue(strEventContent.contains("\"ddsource\": \"dd_debugger\""));
    assertTrue(strEventContent.contains("\"service\": \"service1\""));
    assertTrue(strEventContent.contains("\"type\": \"symdb\""));
    BatchUploader.MultiPartContent symbolContent = symbolUploaderMock.multiPartContents.get(1);
    assertEquals("file", symbolContent.getPartName());
    assertEquals("file.json", symbolContent.getFileName());
    assertEquals(
        "{\"language\":\"JAVA\",\"scopes\":[{\"end_line\":0,\"has_injectible_lines\":false,\"scope_type\":\"JAR\",\"start_line\":0}],\"service\":\"service1\"}",
        new String(symbolContent.getContent()));
  }

  @Test
  public void testMultiScopeFlush() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, MAX_SYMDB_UPLOAD_SIZE);
    symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar1.jar", 0, 0).build());
    symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar2.jar", 0, 0).build());
    symbolSink.flush();
    // only 1 request because we are batching the scopes
    assertEquals(2, symbolUploaderMock.multiPartContents.size());
    String strContent = assertMultipartContent(symbolUploaderMock, 0);
    assertTrue(strContent.contains("\"source_file\":\"jar1.jar\""));
    assertTrue(strContent.contains("\"source_file\":\"jar2.jar\""));
  }

  @Test
  public void testQueueFull() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, MAX_SYMDB_UPLOAD_SIZE);
    for (int i = 0; i < SymbolSink.CAPACITY; i++) {
      symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar1.jar", 0, 0).build());
    }
    assertEquals(0, symbolUploaderMock.multiPartContents.size());
    // implicit flush because Q is full
    symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar2.jar", 0, 0).build());
    assertEquals(2, symbolUploaderMock.multiPartContents.size());
    assertTrue(
        new String(symbolUploaderMock.multiPartContents.get(1).getContent())
            .contains("\"source_file\":\"jar1.jar\""));
    symbolSink.flush();
    assertEquals(4, symbolUploaderMock.multiPartContents.size());
    assertTrue(
        new String(symbolUploaderMock.multiPartContents.get(3).getContent())
            .contains("\"source_file\":\"jar2.jar\""));
  }

  @Test
  public void splitByJarScopes() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, 1024);
    final int NUM_JAR_SCOPES = 10;
    for (int i = 0; i < NUM_JAR_SCOPES; i++) {
      symbolSink.addScope(
          Scope.builder(ScopeType.JAR, "jar" + i + ".jar", 0, 0)
              .scopes(singletonList(Scope.builder(ScopeType.CLASS, "class" + i, 0, 0).build()))
              .build());
    }
    symbolSink.flush();
    // split upload request per jar scope
    assertEquals(NUM_JAR_SCOPES * 2, symbolUploaderMock.multiPartContents.size());
    for (int i = 0; i < NUM_JAR_SCOPES * 2; i += 2) {
      String strContent = assertMultipartContent(symbolUploaderMock, i);
      assertTrue(strContent.contains("\"source_file\":\"jar" + (i / 2) + ".jar\""));
    }
  }

  @Test
  public void splitTooManyJarScopes() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, 4096);
    final int NUM_JAR_SCOPES = 21;
    for (int i = 0; i < NUM_JAR_SCOPES; i++) {
      symbolSink.addScope(
          Scope.builder(ScopeType.JAR, "jar" + i + ".jar", 0, 0)
              .scopes(singletonList(Scope.builder(ScopeType.CLASS, "class" + i, 0, 0).build()))
              .build());
    }
    symbolSink.flush();
    // split upload request per half jar scopes
    assertEquals(2 * 2, symbolUploaderMock.multiPartContents.size());
    String strContent1 = assertMultipartContent(symbolUploaderMock, 0);
    assertTrue(strContent1.contains("\"source_file\":\"jar0.jar\""));
    assertTrue(strContent1.contains("\"source_file\":\"jar9.jar\""));
    String strContent2 = assertMultipartContent(symbolUploaderMock, 2);
    assertTrue(strContent2.contains("\"source_file\":\"jar10.jar\""));
    assertTrue(strContent2.contains("\"source_file\":\"jar20.jar\""));
  }

  @Test
  public void splitByClassScopes() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, 1024);
    final int NUM_CLASS_SCOPES = 10;
    List<Scope> classScopes = new ArrayList<>();
    for (int i = 0; i < NUM_CLASS_SCOPES; i++) {
      classScopes.add(
          Scope.builder(ScopeType.CLASS, "class" + i, 0, 0)
              .scopes(
                  Collections.singletonList(
                      Scope.builder(ScopeType.METHOD, "class" + i, 0, 0)
                          .name("method" + i)
                          .build()))
              .build());
    }
    symbolSink.addScope(
        Scope.builder(ScopeType.JAR, "jar1.jar", 0, 0)
            .name("jar1.jar")
            .scopes(classScopes)
            .build());
    symbolSink.flush();
    // split upload request per jar scope
    final int EXPECTED_REQUESTS = 4;
    assertEquals(EXPECTED_REQUESTS * 2, symbolUploaderMock.multiPartContents.size());
    List<List<String>> expectedSourceFiles =
        Arrays.asList(
            Arrays.asList("class0", "class1"),
            Arrays.asList("class2", "class3", "class4"),
            Arrays.asList("class5", "class6"),
            Arrays.asList("class7", "class8", "class9"));
    for (int i = 0; i < EXPECTED_REQUESTS * 2; i += 2) {
      String strContent = assertMultipartContent(symbolUploaderMock, i);
      for (String sourceFile : expectedSourceFiles.get(i / 2)) {
        assertTrue(strContent.contains("\"source_file\":\"" + sourceFile + "\""));
      }
    }
  }

  @Test
  public void splitByClassScopesImpossible() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(false);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, 1);
    final int NUM_CLASS_SCOPES = 10;
    List<Scope> classScopes = new ArrayList<>();
    for (int i = 0; i < NUM_CLASS_SCOPES; i++) {
      classScopes.add(
          Scope.builder(ScopeType.CLASS, "class" + i, 0, 0)
              .scopes(
                  Collections.singletonList(
                      Scope.builder(ScopeType.METHOD, "class" + i, 0, 0)
                          .name("method" + i)
                          .build()))
              .build());
    }
    symbolSink.addScope(
        Scope.builder(ScopeType.JAR, "jar1.jar", 0, 0)
            .name("jar1.jar")
            .scopes(classScopes)
            .build());
    symbolSink.flush();
    // no request to upload because we cannot split the jar scope
    assertTrue(symbolUploaderMock.multiPartContents.isEmpty());
  }

  @Test
  public void maxCompressedAndSplit() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    when(config.isSymbolDatabaseCompressed()).thenReturn(true);
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock, 512);
    final int NUM_JAR_SCOPES = 100;
    for (int i = 0; i < NUM_JAR_SCOPES; i++) {
      symbolSink.addScope(
          Scope.builder(ScopeType.JAR, "jar" + i + ".jar", 0, 0)
              .scopes(singletonList(Scope.builder(ScopeType.CLASS, "class" + i, 0, 0).build()))
              .build());
    }
    symbolSink.flush();
    assertEquals(4, symbolUploaderMock.multiPartContents.size());
    for (int i = 0; i < 4; i += 2) {
      BatchUploader.MultiPartContent eventContent = symbolUploaderMock.multiPartContents.get(i);
      assertEquals("event", eventContent.getPartName());
      BatchUploader.MultiPartContent symbolContent =
          symbolUploaderMock.multiPartContents.get(i + 1);
      assertEquals(MediaType.get("application/gzip"), symbolContent.getMediaType());
    }
  }

  private static String assertMultipartContent(SymbolUploaderMock symbolUploaderMock, int index) {
    BatchUploader.MultiPartContent eventContent = symbolUploaderMock.multiPartContents.get(index);
    assertEquals("event", eventContent.getPartName());
    BatchUploader.MultiPartContent symbolContent =
        symbolUploaderMock.multiPartContents.get(index + 1);
    assertEquals("file", symbolContent.getPartName());
    return new String(symbolContent.getContent());
  }

  static class SymbolUploaderMock extends BatchUploader {
    final List<MultiPartContent> multiPartContents = new ArrayList<>();

    public SymbolUploaderMock() {
      super("mock", Config.get(), "http://localhost", SymbolSink.RETRY_POLICY);
    }

    @Override
    public void uploadAsMultipart(String tags, MultiPartContent... parts) {
      multiPartContents.addAll(Arrays.asList(parts));
    }
  }
}

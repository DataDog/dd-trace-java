package com.datadog.debugger.sink;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.symbol.Scope;
import com.datadog.debugger.symbol.ScopeType;
import com.datadog.debugger.uploader.BatchUploader;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolSinkTest {

  @Test
  public void testSimpleFlush() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock);
    symbolSink.addScope(Scope.builder(ScopeType.JAR, null, 0, 0).build());
    symbolSink.flush();
    assertEquals(2, symbolUploaderMock.multiPartContents.size());
    BatchUploader.MultiPartContent eventContent = symbolUploaderMock.multiPartContents.get(0);
    assertEquals("event", eventContent.getPartName());
    assertEquals("event.json", eventContent.getFileName());
    String strEventContent = new String(eventContent.getContent());
    assertTrue(strEventContent.contains("\"ddsource\": \"dd_debugger\""));
    assertTrue(strEventContent.contains("\"service\": \"service1\""));
    BatchUploader.MultiPartContent symbolContent = symbolUploaderMock.multiPartContents.get(1);
    assertEquals("file", symbolContent.getPartName());
    assertEquals("file.json", symbolContent.getFileName());
    assertEquals(
        "{\"language\":\"JAVA\",\"scopes\":[{\"end_line\":0,\"scope_type\":\"JAR\",\"start_line\":0}],\"service\":\"service1\"}",
        new String(symbolContent.getContent()));
  }

  @Test
  public void testMultiScopeFlush() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock);
    symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar1.jar", 0, 0).build());
    symbolSink.addScope(Scope.builder(ScopeType.JAR, "jar2.jar", 0, 0).build());
    symbolSink.flush();
    // only 1 request because we are batching the scopes
    assertEquals(2, symbolUploaderMock.multiPartContents.size());
    BatchUploader.MultiPartContent eventContent = symbolUploaderMock.multiPartContents.get(0);
    assertEquals("event", eventContent.getPartName());
    BatchUploader.MultiPartContent symbolContent = symbolUploaderMock.multiPartContents.get(1);
    assertEquals("file", symbolContent.getPartName());
    String strContent = new String(symbolContent.getContent());
    assertTrue(strContent.contains("\"source_file\":\"jar1.jar\""));
    assertTrue(strContent.contains("\"source_file\":\"jar2.jar\""));
  }

  @Test
  public void testQueueFull() {
    SymbolUploaderMock symbolUploaderMock = new SymbolUploaderMock();
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service1");
    SymbolSink symbolSink = new SymbolSink(config, symbolUploaderMock);
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

  static class SymbolUploaderMock extends BatchUploader {
    final List<MultiPartContent> multiPartContents = new ArrayList<>();

    public SymbolUploaderMock() {
      super(Config.get(), "http://localhost", SymbolSink.RETRY_POLICY);
    }

    @Override
    public void uploadAsMultipart(String tags, MultiPartContent... parts) {
      multiPartContents.addAll(Arrays.asList(parts));
    }
  }
}

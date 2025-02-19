package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.util.ClassNameFiltering;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SymbolAggregatorTest {

  @Test
  void testScanQueuedJars() {
    SymbolSink symbolSink = mock(SymbolSink.class);
    SymbolAggregator symbolAggregator =
        spy(new SymbolAggregator(ClassNameFiltering.allowAll(), symbolSink, 1));
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    CodeSource codeSource = new CodeSource(jarFileUrl, (java.security.cert.Certificate[]) null);
    ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
    symbolAggregator.parseClass(null, null, protectionDomain);
    symbolAggregator.scanQueuedJars(null);
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(symbolAggregator, atLeastOnce())
        .parseClass(any(), captor.capture(), any(), eq(jarFileUrl.getFile()));
    // captor.getAllValues().get(0) is the first argument of the first invocation of parseClass with
    // null
    assertEquals(
        "com/datadog/debugger/symbol/SymbolExtraction01.class", captor.getAllValues().get(1));
    assertEquals(
        "BOOT-INF/classes/org/springframework/samples/petclinic/vet/VetController.class",
        captor.getAllValues().get(2));
  }
}

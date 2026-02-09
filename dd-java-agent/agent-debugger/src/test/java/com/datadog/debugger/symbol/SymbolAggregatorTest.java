package com.datadog.debugger.symbol;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.TestClassFileHelper.getClassFileBytes;

import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.util.ClassNameFiltering;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.mockito.ArgumentCaptor;

class SymbolAggregatorTest {

  @Test
  void testScanQueuedJars() {
    SymbolSink symbolSink = mock(SymbolSink.class);
    SymbolAggregator symbolAggregator =
        spy(new SymbolAggregator(ClassNameFiltering.allowAll(), emptyList(), symbolSink, 1));
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

  @Test
  void testScanQueuedCorruptedJars() {
    SymbolSink symbolSink = mock(SymbolSink.class);
    SymbolAggregator symbolAggregator =
        spy(new SymbolAggregator(ClassNameFiltering.allowAll(), emptyList(), symbolSink, 1));
    // add first a corrupted jar
    URL corruptedUrl = getClass().getResource("/com/datadog/debugger/classfiles/CommandLine.class");
    CodeSource corruptedCodeSource =
        new CodeSource(corruptedUrl, (java.security.cert.Certificate[]) null);
    ProtectionDomain corruptedProtectionDomain = new ProtectionDomain(corruptedCodeSource, null);
    symbolAggregator.parseClass(null, null, corruptedProtectionDomain);
    // add second a clean jar
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    CodeSource codeSource = new CodeSource(jarFileUrl, (java.security.cert.Certificate[]) null);
    ProtectionDomain protectionDomain = new ProtectionDomain(codeSource, null);
    symbolAggregator.parseClass(null, null, protectionDomain);
    symbolAggregator.scanQueuedJars(null);
    // clean jar should have been processed
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

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testScopeFilter() {
    ScopeFilter mockFilter = mock(ScopeFilter.class);
    when(mockFilter.filterOut(any())).thenReturn(true);
    SymbolSink symbolSink = mock(SymbolSink.class);
    doAnswer(
            invocation -> {
              Object[] args = invocation.getArguments();
              assertEquals(1, args.length);
              assertInstanceOf(Scope.class, args[0]);
              Scope scope = (Scope) args[0];
              assertTrue(scope.getScopes().isEmpty());
              return null;
            })
        .when(symbolSink)
        .addScope(any());
    SymbolAggregator symbolAggregator =
        new SymbolAggregator(ClassNameFiltering.allowAll(), asList(mockFilter), symbolSink, 1);
    symbolAggregator.parseClass(
        SymDBReport.NO_OP, String.class.getTypeName(), getClassFileBytes(String.class), null);
    verify(mockFilter).filterOut(any());
  }
}

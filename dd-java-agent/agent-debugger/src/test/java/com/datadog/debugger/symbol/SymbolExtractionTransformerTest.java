package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.sink.SymbolSink;
import datadog.trace.api.Config;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SymbolExtractionTransformerTest {
  private static final String SYMBOL_PACKAGE = "com.datadog.debugger.symbol";

  private Instrumentation instr = ByteBuddyAgent.install();
  private Config config;

  @BeforeEach
  public void setUp() {
    config = Mockito.mock(Config.class);
    when(config.getDebuggerSymbolIncludes()).thenReturn(SYMBOL_PACKAGE);
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
  }

  @Test
  public void noIncludesFilterOutDatadogClass() throws IOException, URISyntaxException {
    config = Mockito.mock(Config.class);
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction01";
    final String SOURCE_FILE = "SymbolExtraction01.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    assertFalse(
        symbolSinkMock.jarScopes.stream()
            .flatMap(scope -> scope.getScopes().stream())
            .anyMatch(scope -> scope.getName().equals(CLASS_NAME)));
  }

  @Test
  public void symbolExtraction01() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction01";
    final String SOURCE_FILE = "SymbolExtraction01.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 2, 20, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 2, 2, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 4, 20, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 4);
    assertEquals(4, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 8, 15, SOURCE_FILE);
    assertEquals(3, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "foo", Integer.TYPE.getTypeName(), 8);
    assertSymbol(
        local0.getSymbols().get(1), SymbolType.LOCAL, "bar", Integer.TYPE.getTypeName(), 9);
    assertSymbol(local0.getSymbols().get(2), SymbolType.LOCAL, "j", Integer.TYPE.getTypeName(), 11);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 7, 15, SOURCE_FILE);
    assertEquals(1, local1.getSymbols().size());
    assertSymbol(local1.getSymbols().get(0), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 7);
    Scope local2 = mainMethodScope.getScopes().get(2);
    assertScope(local2, ScopeType.LOCAL, null, 6, 17, SOURCE_FILE);
    assertEquals(1, local2.getSymbols().size());
    assertSymbol(
        local2.getSymbols().get(0), SymbolType.LOCAL, "var2", Integer.TYPE.getTypeName(), 6);
    Scope local3 = mainMethodScope.getScopes().get(3);
    assertScope(local3, ScopeType.LOCAL, null, 4, 20, SOURCE_FILE);
    assertEquals(3, local3.getSymbols().size());
    assertSymbol(
        local3.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 4);
    assertSymbol(
        local3.getSymbols().get(1), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 4);
    assertSymbol(
        local3.getSymbols().get(2), SymbolType.LOCAL, "var3", Integer.TYPE.getTypeName(), 19);
  }

  @Test
  public void symbolExtraction02() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction02";
    final String SOURCE_FILE = "SymbolExtraction02.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 5, 6, SOURCE_FILE);
    assertEquals(1, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "var1", String.class.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction03() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction03";
    final String SOURCE_FILE = "SymbolExtraction03.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 4, 28, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 4, 4, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 28, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 6);
    assertEquals(3, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 20, 21, SOURCE_FILE);
    assertEquals(1, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "var4", String.class.getTypeName(), 20);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 12, 24, SOURCE_FILE);
    assertEquals(4, local1.getSymbols().size());
    assertSymbol(
        local1.getSymbols().get(0), SymbolType.LOCAL, "var31", String.class.getTypeName(), 12);
    assertSymbol(
        local1.getSymbols().get(1), SymbolType.LOCAL, "var32", String.class.getTypeName(), 13);
    assertSymbol(
        local1.getSymbols().get(2), SymbolType.LOCAL, "var30", String.class.getTypeName(), 15);
    assertSymbol(
        local1.getSymbols().get(3), SymbolType.LOCAL, "var3", String.class.getTypeName(), 17);
    Scope local2 = mainMethodScope.getScopes().get(2);
    assertScope(local2, ScopeType.LOCAL, null, 6, 28, SOURCE_FILE);
    assertEquals(3, local2.getSymbols().size());
    assertSymbol(
        local2.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 6);
    assertSymbol(
        local2.getSymbols().get(1), SymbolType.LOCAL, "var1", String.class.getTypeName(), 6);
    assertSymbol(
        local2.getSymbols().get(2), SymbolType.LOCAL, "var5", String.class.getTypeName(), 27);
  }

  @Test
  public void symbolExtraction04() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction04";
    final String SOURCE_FILE = "SymbolExtraction04.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 18, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 18, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(5, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 10, 12, SOURCE_FILE);
    assertEquals(1, local0.getSymbols().size());
    assertSymbol(local0.getSymbols().get(0), SymbolType.LOCAL, "k", Integer.TYPE.getTypeName(), 10);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 9, 15, SOURCE_FILE);
    assertEquals(2, local1.getSymbols().size());
    assertSymbol(
        local1.getSymbols().get(0), SymbolType.LOCAL, "var3", String.class.getTypeName(), 9);
    assertSymbol(
        local1.getSymbols().get(1), SymbolType.LOCAL, "var5", String.class.getTypeName(), 14);
    Scope local2 = mainMethodScope.getScopes().get(2);
    assertScope(local2, ScopeType.LOCAL, null, 7, 15, SOURCE_FILE);
    assertEquals(2, local2.getSymbols().size());
    assertSymbol(local2.getSymbols().get(0), SymbolType.LOCAL, "j", Integer.TYPE.getTypeName(), 8);
    assertSymbol(
        local2.getSymbols().get(1), SymbolType.LOCAL, "var2", String.class.getTypeName(), 7);
    Scope local3 = mainMethodScope.getScopes().get(3);
    assertScope(local3, ScopeType.LOCAL, null, 6, 15, SOURCE_FILE);
    assertEquals(1, local3.getSymbols().size());
    assertSymbol(local3.getSymbols().get(0), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 6);
    Scope local4 = mainMethodScope.getScopes().get(4);
    assertScope(local4, ScopeType.LOCAL, null, 5, 18, SOURCE_FILE);
    assertEquals(2, local4.getSymbols().size());
    assertSymbol(
        local4.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 5);
    assertSymbol(
        local4.getSymbols().get(1), SymbolType.LOCAL, "var1", String.class.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction05() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction05";
    final String SOURCE_FILE = "SymbolExtraction05.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 15, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 15, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(2, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 7, 13, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 7);
    assertSymbol(local0.getSymbols().get(1), SymbolType.LOCAL, "j", Integer.TYPE.getTypeName(), 8);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 5, 15, SOURCE_FILE);
    assertEquals(2, local1.getSymbols().size());
    assertSymbol(
        local1.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 5);
    assertSymbol(local1.getSymbols().get(1), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction06() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction06";
    final String SOURCE_FILE = "SymbolExtraction06.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 13, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 13, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(2, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 9, 11, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "var3", Integer.TYPE.getTypeName(), 10);
    assertSymbol(
        local0.getSymbols().get(1),
        SymbolType.LOCAL,
        "rte",
        RuntimeException.class.getTypeName(),
        9);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 5, 13, SOURCE_FILE);
    assertEquals(2, local1.getSymbols().size());
    assertSymbol(
        local1.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 5);
    assertSymbol(
        local1.getSymbols().get(1), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction07() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction07";
    final String SOURCE_FILE = "SymbolExtraction07.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 10, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 10, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 5, 10, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 5);
    assertSymbol(local0.getSymbols().get(1), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction08() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction08";
    final String SOURCE_FILE = "SymbolExtraction08.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 11, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(2, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 8, 9, SOURCE_FILE);
    assertEquals(1, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "var3", Integer.TYPE.getTypeName(), 8);
    Scope local1 = mainMethodScope.getScopes().get(1);
    assertScope(local1, ScopeType.LOCAL, null, 5, 11, SOURCE_FILE);
    assertEquals(2, local1.getSymbols().size());
    assertSymbol(
        local1.getSymbols().get(0), SymbolType.LOCAL, "arg", String.class.getTypeName(), 5);
    assertSymbol(
        local1.getSymbols().get(1), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 5);
  }

  @Test
  public void symbolExtraction09() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction09";
    final String SOURCE_FILE = "SymbolExtraction09.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 5, 12, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(3, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 5, 5, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 7, 12, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 7);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 7, 12, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "outside", Integer.TYPE.getTypeName(), 7);
    assertSymbol(
        local0.getSymbols().get(1), SymbolType.LOCAL, "lambda", Supplier.class.getTypeName(), 8);
    Scope lambdaMethodScope = classScope.getScopes().get(2);
    assertScope(lambdaMethodScope, ScopeType.METHOD, "lambda$main$0", 9, 10, SOURCE_FILE);
    assertEquals(1, lambdaMethodScope.getSymbols().size());
    assertSymbol(
        lambdaMethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "outside",
        Integer.TYPE.getTypeName(),
        9);
    assertEquals(1, lambdaMethodScope.getScopes().size());
    Scope lambdaLocal0 = lambdaMethodScope.getScopes().get(0);
    assertScope(lambdaLocal0, ScopeType.LOCAL, null, 9, 10, SOURCE_FILE);
    assertEquals(1, lambdaLocal0.getSymbols().size());
    assertSymbol(
        lambdaLocal0.getSymbols().get(0), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 9);
  }

  @Test
  public void symbolExtraction10() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction10";
    final String SOURCE_FILE = "SymbolExtraction10.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    transformer.flushScopes(null);
    assertEquals(2, symbolSinkMock.jarScopes.get(0).getScopes().size());
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 5, 6, SOURCE_FILE);
    assertEquals(1, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0),
        SymbolType.LOCAL,
        "winner",
        "com.datadog.debugger.symbol.SymbolExtraction10$Inner",
        5);
    Scope innerClassScope = symbolSinkMock.jarScopes.get(0).getScopes().get(1);
    assertScope(innerClassScope, ScopeType.CLASS, CLASS_NAME + "$Inner", 9, 13, SOURCE_FILE);
    assertEquals(1, innerClassScope.getSymbols().size());
    assertEquals(2, innerClassScope.getScopes().size());
    assertSymbol(
        innerClassScope.getSymbols().get(0),
        SymbolType.FIELD,
        "field1",
        Integer.TYPE.getTypeName(),
        0);
    assertScope(innerClassScope.getScopes().get(0), ScopeType.METHOD, "<init>", 9, 10, SOURCE_FILE);
    Scope addToMethod = innerClassScope.getScopes().get(1);
    assertScope(addToMethod, ScopeType.METHOD, "addTo", 12, 13, SOURCE_FILE);
    assertEquals(1, addToMethod.getSymbols().size());
    assertSymbol(
        addToMethod.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 12);
    assertEquals(1, addToMethod.getScopes().size());
    Scope addToLocal0 = addToMethod.getScopes().get(0);
    assertScope(addToLocal0, ScopeType.LOCAL, null, 12, 13, SOURCE_FILE);
    assertEquals(1, addToLocal0.getSymbols().size());
    assertSymbol(
        addToLocal0.getSymbols().get(0), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 12);
  }

  @Test
  public void symbolExtraction11() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction11";
    final String SOURCE_FILE = "SymbolExtraction11.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", 1).get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE);
    assertEquals(1, classScope.getSymbols().size());
    assertSymbol(
        classScope.getSymbols().get(0), SymbolType.FIELD, "field1", Integer.TYPE.getTypeName(), 0);
    assertEquals(2, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 4, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 11, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 6);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 6, 11, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(
        local0.getSymbols().get(0), SymbolType.LOCAL, "arg", Integer.TYPE.getTypeName(), 6);
    assertSymbol(
        local0.getSymbols().get(1), SymbolType.LOCAL, "var1", Integer.TYPE.getTypeName(), 6);
  }

  @Test
  public void symbolExtraction12() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + ".SymbolExtraction12";
    final String SOURCE_FILE = "SymbolExtraction12.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(symbolSinkMock, config);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", 1).get();
    transformer.flushScopes(null);
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 6, 20, SOURCE_FILE);
    assertEquals(0, classScope.getSymbols().size());
    assertEquals(7, classScope.getScopes().size());
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 6, 6, SOURCE_FILE);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 8, 13, SOURCE_FILE);
    assertEquals(1, mainMethodScope.getSymbols().size());
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 8);
    assertEquals(1, mainMethodScope.getScopes().size());
    Scope local0 = mainMethodScope.getScopes().get(0);
    assertScope(local0, ScopeType.LOCAL, null, 8, 13, SOURCE_FILE);
    assertEquals(2, local0.getSymbols().size());
    assertSymbol(local0.getSymbols().get(0), SymbolType.LOCAL, "list", List.class.getTypeName(), 8);
    assertSymbol(
        local0.getSymbols().get(1), SymbolType.LOCAL, "sum", Integer.TYPE.getTypeName(), 12);
    Scope fooMethodScope = classScope.getScopes().get(2);
    assertScope(fooMethodScope, ScopeType.METHOD, "foo", 17, 20, SOURCE_FILE);
    assertEquals(1, fooMethodScope.getSymbols().size());
    assertSymbol(
        fooMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 17);
    Scope lambdaFoo3MethodScope = classScope.getScopes().get(3);
    assertScope(lambdaFoo3MethodScope, ScopeType.METHOD, "lambda$foo$3", 19, 19, SOURCE_FILE);
    assertEquals(1, lambdaFoo3MethodScope.getSymbols().size());
    assertSymbol(
        lambdaFoo3MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        19);
    Scope lambdaFoo2MethodScope = classScope.getScopes().get(4);
    assertScope(lambdaFoo2MethodScope, ScopeType.METHOD, "lambda$foo$2", 19, 19, SOURCE_FILE);
    assertEquals(1, lambdaFoo2MethodScope.getSymbols().size());
    assertSymbol(
        lambdaFoo2MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.class.getTypeName(),
        19);
    Scope lambdaMain1MethodScope = classScope.getScopes().get(5);
    assertScope(lambdaMain1MethodScope, ScopeType.METHOD, "lambda$main$1", 11, 11, SOURCE_FILE);
    assertEquals(1, lambdaMain1MethodScope.getSymbols().size());
    assertSymbol(
        lambdaMain1MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        11);
    Scope lambdaMain0MethodScope = classScope.getScopes().get(6);
    assertScope(lambdaMain0MethodScope, ScopeType.METHOD, "lambda$main$0", 11, 11, SOURCE_FILE);
    assertEquals(1, lambdaMain0MethodScope.getSymbols().size());
    assertSymbol(
        lambdaMain0MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.class.getTypeName(),
        11);
  }

  private static void assertScope(
      Scope scope,
      ScopeType scopeType,
      String name,
      int startLine,
      int endLine,
      String sourceFile) {
    assertEquals(scopeType, scope.getScopeType());
    assertEquals(name, scope.getName());
    assertEquals(startLine, scope.getStartLine());
    assertEquals(endLine, scope.getEndLine());
    assertEquals(sourceFile, scope.getSourceFile());
  }

  private void assertSymbol(
      Symbol symbol, SymbolType symbolType, String name, String type, int line) {
    assertEquals(symbolType, symbol.getSymbolType());
    assertEquals(name, symbol.getName());
    assertEquals(type, symbol.getType());
    assertEquals(line, symbol.getLine());
  }

  static class SymbolSinkMock extends SymbolSink {
    final List<Scope> jarScopes = new ArrayList<>();

    public SymbolSinkMock(Config config) {
      super(config);
    }

    @Override
    public boolean addScope(Scope jarScope) {
      return jarScopes.add(jarScope);
    }
  }
}

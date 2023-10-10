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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 2, 20, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 2, 2, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 4, 20, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 4);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 4, 20, SOURCE_FILE, 1, 2);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        4);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(1),
        SymbolType.LOCAL,
        "var3",
        Integer.TYPE.getTypeName(),
        19);
    Scope ifLine5Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(ifLine5Scope, ScopeType.LOCAL, null, 6, 17, SOURCE_FILE, 1, 1);
    assertSymbol(
        ifLine5Scope.getSymbols().get(0), SymbolType.LOCAL, "var2", Integer.TYPE.getTypeName(), 6);
    Scope forLine7Scope = ifLine5Scope.getScopes().get(0);
    assertScope(forLine7Scope, ScopeType.LOCAL, null, 7, 15, SOURCE_FILE, 1, 1);
    assertSymbol(
        forLine7Scope.getSymbols().get(0), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 7);
    Scope forBodyLine7Scope = forLine7Scope.getScopes().get(0);
    assertScope(forBodyLine7Scope, ScopeType.LOCAL, null, 8, 15, SOURCE_FILE, 1, 3);
    assertSymbol(
        forBodyLine7Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "foo",
        Integer.TYPE.getTypeName(),
        8);
    assertSymbol(
        forBodyLine7Scope.getSymbols().get(1),
        SymbolType.LOCAL,
        "bar",
        Integer.TYPE.getTypeName(),
        9);
    assertSymbol(
        forBodyLine7Scope.getSymbols().get(2),
        SymbolType.LOCAL,
        "j",
        Integer.TYPE.getTypeName(),
        11);
    Scope whileLine12 = forBodyLine7Scope.getScopes().get(0);
    assertScope(whileLine12, ScopeType.LOCAL, null, 13, 14, SOURCE_FILE, 0, 1);
    assertSymbol(
        whileLine12.getSymbols().get(0), SymbolType.LOCAL, "var4", Integer.TYPE.getTypeName(), 13);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 6, SOURCE_FILE, 0, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        String.class.getTypeName(),
        5);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 4, 28, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 4, 4, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 28, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 6);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 6, 28, SOURCE_FILE, 2, 2);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        String.class.getTypeName(),
        6);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(1),
        SymbolType.LOCAL,
        "var5",
        String.class.getTypeName(),
        27);
    Scope elseLine10Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(elseLine10Scope, ScopeType.LOCAL, null, 12, 24, SOURCE_FILE, 1, 4);
    assertSymbol(
        elseLine10Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var31",
        String.class.getTypeName(),
        12);
    assertSymbol(
        elseLine10Scope.getSymbols().get(1),
        SymbolType.LOCAL,
        "var32",
        String.class.getTypeName(),
        13);
    assertSymbol(
        elseLine10Scope.getSymbols().get(2),
        SymbolType.LOCAL,
        "var30",
        String.class.getTypeName(),
        15);
    assertSymbol(
        elseLine10Scope.getSymbols().get(3),
        SymbolType.LOCAL,
        "var3",
        String.class.getTypeName(),
        17);
    Scope ifLine19Scope = elseLine10Scope.getScopes().get(0);
    assertScope(ifLine19Scope, ScopeType.LOCAL, null, 20, 21, SOURCE_FILE, 0, 1);
    assertSymbol(
        ifLine19Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var4",
        String.class.getTypeName(),
        20);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 18, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 18, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 18, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        String.class.getTypeName(),
        5);
    Scope forLine6Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(forLine6Scope, ScopeType.LOCAL, null, 6, 15, SOURCE_FILE, 1, 1);
    assertSymbol(
        forLine6Scope.getSymbols().get(0), SymbolType.LOCAL, "i", Integer.TYPE.getTypeName(), 6);
    Scope forBodyLine6Scope = forLine6Scope.getScopes().get(0);
    assertScope(forBodyLine6Scope, ScopeType.LOCAL, null, 7, 15, SOURCE_FILE, 1, 2);
    assertSymbol(
        forBodyLine6Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "j",
        Integer.TYPE.getTypeName(),
        8);
    assertSymbol(
        forBodyLine6Scope.getSymbols().get(1),
        SymbolType.LOCAL,
        "var2",
        String.class.getTypeName(),
        7);
    Scope forBodyLine8Scope = forBodyLine6Scope.getScopes().get(0);
    assertScope(forBodyLine8Scope, ScopeType.LOCAL, null, 9, 15, SOURCE_FILE, 1, 2);
    assertSymbol(
        forBodyLine8Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var3",
        String.class.getTypeName(),
        9);
    assertSymbol(
        forBodyLine8Scope.getSymbols().get(1),
        SymbolType.LOCAL,
        "var5",
        String.class.getTypeName(),
        14);
    Scope forLine10Scope = forBodyLine8Scope.getScopes().get(0);
    assertScope(forLine10Scope, ScopeType.LOCAL, null, 10, 12, SOURCE_FILE, 1, 1);
    assertSymbol(
        forLine10Scope.getSymbols().get(0), SymbolType.LOCAL, "k", Integer.TYPE.getTypeName(), 10);
    Scope forBodyLine10Scope = forLine10Scope.getScopes().get(0);
    assertScope(forBodyLine10Scope, ScopeType.LOCAL, null, 11, 12, SOURCE_FILE, 0, 1);
    assertSymbol(
        forBodyLine10Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var4",
        String.class.getTypeName(),
        11);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 15, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 15, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 15, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "i",
        Integer.TYPE.getTypeName(),
        5);
    Scope whileLine6Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(whileLine6Scope, ScopeType.LOCAL, null, 7, 13, SOURCE_FILE, 1, 2);
    assertSymbol(
        whileLine6Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        7);
    assertSymbol(
        whileLine6Scope.getSymbols().get(1), SymbolType.LOCAL, "j", Integer.TYPE.getTypeName(), 8);
    Scope whileLine9Scope = whileLine6Scope.getScopes().get(0);
    assertScope(whileLine9Scope, ScopeType.LOCAL, null, 10, 11, SOURCE_FILE, 0, 1);
    assertSymbol(
        whileLine9Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var2",
        Integer.TYPE.getTypeName(),
        10);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 13, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 13, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 13, SOURCE_FILE, 2, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        5);
    Scope catchLine9Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(catchLine9Scope, ScopeType.LOCAL, null, 9, 11, SOURCE_FILE, 0, 2);
    assertSymbol(
        catchLine9Scope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var3",
        Integer.TYPE.getTypeName(),
        10);
    assertSymbol(
        catchLine9Scope.getSymbols().get(1),
        SymbolType.LOCAL,
        "rte",
        RuntimeException.class.getTypeName(),
        9);
    Scope tryLine6Scope = mainMethodLocalScope.getScopes().get(1);
    assertScope(tryLine6Scope, ScopeType.LOCAL, null, 7, 8, SOURCE_FILE, 0, 1);
    assertSymbol(
        tryLine6Scope.getSymbols().get(0), SymbolType.LOCAL, "var2", Integer.TYPE.getTypeName(), 7);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 10, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 10, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 10, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "i",
        Integer.TYPE.getTypeName(),
        5);
    Scope doLine6Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(doLine6Scope, ScopeType.LOCAL, null, 7, 8, SOURCE_FILE, 0, 1);
    assertSymbol(
        doLine6Scope.getSymbols().get(0), SymbolType.LOCAL, "j", Integer.TYPE.getTypeName(), 7);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 11, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 11, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        5);
    Scope line6Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(line6Scope, ScopeType.LOCAL, null, 7, 9, SOURCE_FILE, 0, 2);
    assertSymbol(
        line6Scope.getSymbols().get(0), SymbolType.LOCAL, "var2", Integer.TYPE.getTypeName(), 7);
    assertSymbol(
        line6Scope.getSymbols().get(1), SymbolType.LOCAL, "var3", Integer.TYPE.getTypeName(), 8);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 5, 12, SOURCE_FILE, 3, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 5, 5, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 7, 12, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 7);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 7, 12, SOURCE_FILE, 0, 2);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "outside",
        Integer.TYPE.getTypeName(),
        7);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(1),
        SymbolType.LOCAL,
        "lambda",
        Supplier.class.getTypeName(),
        8);
    Scope lambdaMethodScope = classScope.getScopes().get(2);
    assertScope(lambdaMethodScope, ScopeType.METHOD, "lambda$main$0", 9, 10, SOURCE_FILE, 1, 1);
    assertSymbol(
        lambdaMethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "outside",
        Integer.TYPE.getTypeName(),
        9);
    Scope lambdaMethodLocalScope = lambdaMethodScope.getScopes().get(0);
    assertScope(lambdaMethodLocalScope, ScopeType.LOCAL, null, 9, 10, SOURCE_FILE, 0, 1);
    assertSymbol(
        lambdaMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        9);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 6, SOURCE_FILE, 0, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "winner",
        "com.datadog.debugger.symbol.SymbolExtraction10$Inner",
        5);
    Scope innerClassScope = symbolSinkMock.jarScopes.get(0).getScopes().get(1);
    assertScope(innerClassScope, ScopeType.CLASS, CLASS_NAME + "$Inner", 9, 13, SOURCE_FILE, 2, 1);
    assertSymbol(
        innerClassScope.getSymbols().get(0),
        SymbolType.FIELD,
        "field1",
        Integer.TYPE.getTypeName(),
        0);
    assertScope(
        innerClassScope.getScopes().get(0), ScopeType.METHOD, "<init>", 9, 10, SOURCE_FILE, 0, 0);
    Scope addToMethod = innerClassScope.getScopes().get(1);
    assertScope(addToMethod, ScopeType.METHOD, "addTo", 12, 13, SOURCE_FILE, 1, 1);
    assertSymbol(
        addToMethod.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 12);
    Scope addToMethodLocalScope = addToMethod.getScopes().get(0);
    assertScope(addToMethodLocalScope, ScopeType.LOCAL, null, 12, 13, SOURCE_FILE, 0, 1);
    assertSymbol(
        addToMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        12);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE, 2, 1);
    assertSymbol(
        classScope.getSymbols().get(0), SymbolType.FIELD, "field1", Integer.TYPE.getTypeName(), 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 4, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 11, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 6);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 6, 11, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        6);
    Scope ifLine7Scope = mainMethodLocalScope.getScopes().get(0);
    assertScope(ifLine7Scope, ScopeType.LOCAL, null, 8, 9, SOURCE_FILE, 0, 1);
    assertSymbol(
        ifLine7Scope.getSymbols().get(0), SymbolType.LOCAL, "var2", Integer.TYPE.getTypeName(), 8);
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
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 6, 20, SOURCE_FILE, 7, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 6, 6, SOURCE_FILE, 0, 0);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 8, 13, SOURCE_FILE, 1, 1);
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 8);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 8, 13, SOURCE_FILE, 0, 2);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "list",
        List.class.getTypeName(),
        8);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(1),
        SymbolType.LOCAL,
        "sum",
        Integer.TYPE.getTypeName(),
        12);
    Scope fooMethodScope = classScope.getScopes().get(2);
    assertScope(fooMethodScope, ScopeType.METHOD, "foo", 17, 20, SOURCE_FILE, 0, 1);
    assertSymbol(
        fooMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 17);
    Scope lambdaFoo3MethodScope = classScope.getScopes().get(3);
    assertScope(lambdaFoo3MethodScope, ScopeType.METHOD, "lambda$foo$3", 19, 19, SOURCE_FILE, 0, 1);
    assertSymbol(
        lambdaFoo3MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        19);
    Scope lambdaFoo2MethodScope = classScope.getScopes().get(4);
    assertScope(lambdaFoo2MethodScope, ScopeType.METHOD, "lambda$foo$2", 19, 19, SOURCE_FILE, 0, 1);
    assertSymbol(
        lambdaFoo2MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.class.getTypeName(),
        19);
    Scope lambdaMain1MethodScope = classScope.getScopes().get(5);
    assertScope(
        lambdaMain1MethodScope, ScopeType.METHOD, "lambda$main$1", 11, 11, SOURCE_FILE, 0, 1);
    assertSymbol(
        lambdaMain1MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        11);
    Scope lambdaMain0MethodScope = classScope.getScopes().get(6);
    assertScope(
        lambdaMain0MethodScope, ScopeType.METHOD, "lambda$main$0", 11, 11, SOURCE_FILE, 0, 1);
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
      String sourceFile,
      int nbScopes,
      int nbSymbols) {
    assertEquals(scopeType, scope.getScopeType());
    assertEquals(name, scope.getName());
    assertEquals(startLine, scope.getStartLine());
    assertEquals(endLine, scope.getEndLine());
    assertEquals(sourceFile, scope.getSourceFile());
    assertEquals(nbScopes, scope.getScopes().size());
    assertEquals(nbSymbols, scope.getSymbols().size());
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

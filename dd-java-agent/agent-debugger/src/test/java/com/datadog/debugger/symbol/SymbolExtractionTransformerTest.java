package com.datadog.debugger.symbol;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.Config;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mockito;

class SymbolExtractionTransformerTest {
  private static final String SYMBOL_PACKAGE = "com.datadog.debugger.symboltest.";
  private static final String EXCLUDED_PACKAGE = "akka.actor.";
  private static final String SYMBOL_PACKAGE_DIR = SYMBOL_PACKAGE.replace('.', '/');
  private static final Set<String> TRANSFORMER_EXCLUDES =
      Stream.of(
              "java.",
              "jdk.",
              "sun.",
              "com.sun.",
              "utils.",
              "javax.",
              "javaslang.",
              "org.omg.",
              "org.joor.",
              "com.datadog.debugger.")
          .collect(Collectors.toSet());

  private Instrumentation instr = ByteBuddyAgent.install();
  private Config config;

  @BeforeEach
  public void setUp() {
    config = Mockito.mock(Config.class);
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction01() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction01";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction01.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 2, 20, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 2, 2, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "2-2");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 4, 20, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "4-15", "17-17", "19-20");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction02() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction02";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction02.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-6");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction03() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction03";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction03.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 4, 28, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 4, 4, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "4-4");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 28, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "6-21", "23-24", "27-28");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction04() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction04";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction04.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 18, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 18, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-12", "14-15", "18-18");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction05() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction05";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction05.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 15, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 15, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-15");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction06() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction06";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction06.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 13, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 13, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-5", "7-11", "13-13");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction07() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction07";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction07.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 10, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 10, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-5", "7-10");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction08() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction08";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction08.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 11, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-5", "7-9", "11-11");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction09() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction09";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction09.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 5, 23, SOURCE_FILE, 6, 2);
    assertSymbol(
        classScope.getSymbols().get(0),
        SymbolType.STATIC_FIELD,
        "staticIntField",
        Integer.TYPE.getTypeName(),
        0);
    assertSymbol(
        classScope.getSymbols().get(1),
        SymbolType.FIELD,
        "intField",
        Integer.TYPE.getTypeName(),
        0);
    assertScope(
        classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 5, 17, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "5-5", "17-17");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 8, 14, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "8-10", "14-14");
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 8);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 8, 14, SOURCE_FILE, 0, 3);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "outside",
        Integer.TYPE.getTypeName(),
        8);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(1),
        SymbolType.LOCAL,
        "outside2",
        Integer.TYPE.getTypeName(),
        9);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(2),
        SymbolType.LOCAL,
        "lambda",
        Supplier.class.getTypeName(),
        10);
    Scope processMethodScope = classScope.getScopes().get(2);
    assertScope(processMethodScope, ScopeType.METHOD, "process", 19, 23, SOURCE_FILE, 1, 0);
    assertLineRanges(processMethodScope, "19-19", "23-23");
    Scope processMethodLocalScope = processMethodScope.getScopes().get(0);
    assertScope(processMethodLocalScope, ScopeType.LOCAL, null, 19, 23, SOURCE_FILE, 0, 1);
    assertSymbol(
        processMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "supplier",
        Supplier.class.getTypeName(),
        19);
    Scope supplierClosureScope = classScope.getScopes().get(3);
    assertScope(
        supplierClosureScope, ScopeType.CLOSURE, "lambda$process$*", 20, 21, SOURCE_FILE, 1, 0);
    assertLineRanges(supplierClosureScope, "20-21");
    Scope supplierClosureLocalScope = supplierClosureScope.getScopes().get(0);
    assertScope(supplierClosureLocalScope, ScopeType.LOCAL, null, 20, 21, SOURCE_FILE, 0, 1);
    assertSymbol(
        supplierClosureLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        20);
    Scope lambdaClosureScope = classScope.getScopes().get(4);
    assertScope(lambdaClosureScope, ScopeType.CLOSURE, "lambda$main$0", 11, 12, SOURCE_FILE, 1, 1);
    assertLineRanges(lambdaClosureScope, "11-12");
    assertSymbol(
        lambdaClosureScope.getSymbols().get(0),
        SymbolType.ARG,
        "outside",
        Integer.TYPE.getTypeName(),
        11);
    Scope lambdaMethodLocalScope = lambdaClosureScope.getScopes().get(0);
    assertScope(lambdaMethodLocalScope, ScopeType.LOCAL, null, 11, 12, SOURCE_FILE, 0, 1);
    assertSymbol(
        lambdaMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "var1",
        Integer.TYPE.getTypeName(),
        11);
    Scope clinitMethodScope = classScope.getScopes().get(5);
    assertScope(clinitMethodScope, ScopeType.METHOD, "<clinit>", 6, 6, SOURCE_FILE, 0, 0);
    assertLineRanges(clinitMethodScope, "6-6");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction10() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction10";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction10.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock, 2);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    assertEquals(2, symbolSinkMock.jarScopes.get(0).getScopes().size());
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 6, SOURCE_FILE, 2, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 3, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-3");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 5, 6, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "5-6");
    assertSymbol(
        mainMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", String.class.getTypeName(), 5);
    Scope mainMethodLocalScope = mainMethodScope.getScopes().get(0);
    assertScope(mainMethodLocalScope, ScopeType.LOCAL, null, 5, 6, SOURCE_FILE, 0, 1);
    assertSymbol(
        mainMethodLocalScope.getSymbols().get(0),
        SymbolType.LOCAL,
        "winner",
        "com.datadog.debugger.symboltest.SymbolExtraction10$Inner",
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
    assertLineRanges(innerClassScope.getScopes().get(0), "9-10");
    Scope addToMethod = innerClassScope.getScopes().get(1);
    assertScope(addToMethod, ScopeType.METHOD, "addTo", 12, 13, SOURCE_FILE, 1, 1);
    assertLineRanges(addToMethod, "12-13");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction11() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction11";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction11.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", 1).get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 3, 11, SOURCE_FILE, 2, 1);
    assertSymbol(
        classScope.getSymbols().get(0), SymbolType.FIELD, "field1", Integer.TYPE.getTypeName(), 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 3, 4, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "3-4");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 6, 11, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "6-9", "11-11");
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
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction12() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction12";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction12.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", 1).get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 6, 20, SOURCE_FILE, 7, 0);
    assertScope(classScope.getScopes().get(0), ScopeType.METHOD, "<init>", 6, 6, SOURCE_FILE, 0, 0);
    assertLineRanges(classScope.getScopes().get(0), "6-6");
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 8, 13, SOURCE_FILE, 1, 1);
    assertLineRanges(mainMethodScope, "8-13");
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
    assertLineRanges(fooMethodScope, "17-20");
    assertSymbol(
        fooMethodScope.getSymbols().get(0), SymbolType.ARG, "arg", Integer.TYPE.getTypeName(), 17);
    Scope lambdaFoo3MethodScope = classScope.getScopes().get(3);
    assertScope(
        lambdaFoo3MethodScope, ScopeType.CLOSURE, "lambda$foo$*", 19, 19, SOURCE_FILE, 0, 1);
    assertLineRanges(lambdaFoo3MethodScope, "19-19");
    assertSymbol(
        lambdaFoo3MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        19);
    Scope lambdaFoo2MethodScope = classScope.getScopes().get(4);
    assertScope(
        lambdaFoo2MethodScope, ScopeType.CLOSURE, "lambda$foo$*", 19, 19, SOURCE_FILE, 0, 1);
    assertLineRanges(lambdaFoo2MethodScope, "19-19");
    assertSymbol(
        lambdaFoo2MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.class.getTypeName(),
        19);
    Scope lambdaMain1MethodScope = classScope.getScopes().get(5);
    assertScope(
        lambdaMain1MethodScope, ScopeType.CLOSURE, "lambda$main$1", 11, 11, SOURCE_FILE, 0, 1);
    assertLineRanges(lambdaMain1MethodScope, "11-11");
    assertSymbol(
        lambdaMain1MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.TYPE.getTypeName(),
        11);
    Scope lambdaMain0MethodScope = classScope.getScopes().get(6);
    assertScope(
        lambdaMain0MethodScope, ScopeType.CLOSURE, "lambda$main$0", 11, 11, SOURCE_FILE, 0, 1);
    assertLineRanges(lambdaMain0MethodScope, "11-11");
    assertSymbol(
        lambdaMain0MethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "x",
        Integer.class.getTypeName(),
        11);
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction13() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction13";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertLangSpecifics(
        classScope.getLanguageSpecifics(),
        asList("public"),
        asList(
            "@com.datadog.debugger.symboltest.MyAnnotation",
            "@com.datadog.debugger.symboltest.MyMarker"),
        Object.class.getTypeName(),
        null,
        null);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertLangSpecifics(
        mainMethodScope.getLanguageSpecifics(),
        asList("public", "static"),
        asList("@com.datadog.debugger.symboltest.MyAnnotation"),
        null,
        null,
        Integer.TYPE.getTypeName());
    assertEquals(3, classScope.getSymbols().size());
    Symbol intField = classScope.getSymbols().get(0);
    assertLangSpecifics(
        intField.getLanguageSpecifics(),
        asList("private"),
        asList("@com.datadog.debugger.symboltest.MyAnnotation"),
        null,
        null,
        null);
    Scope myAnnotationClassScope = symbolSinkMock.jarScopes.get(1).getScopes().get(0);
    assertLangSpecifics(
        myAnnotationClassScope.getLanguageSpecifics(),
        asList("interface", "abstract", "annotation"),
        asList("@java.lang.annotation.Target", "@java.lang.annotation.Retention"),
        Object.class.getTypeName(),
        asList("java.lang.annotation.Annotation"),
        null);
    Symbol strField = classScope.getSymbols().get(1);
    assertLangSpecifics(
        strField.getLanguageSpecifics(),
        asList("public", "static", "volatile"),
        null,
        null,
        null,
        null);
    Symbol doubleField = classScope.getSymbols().get(2);
    assertLangSpecifics(
        doubleField.getLanguageSpecifics(),
        asList("protected", "final", "transient"),
        null,
        null,
        null,
        null);
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction14() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction14";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertLangSpecifics(
        classScope.getLanguageSpecifics(),
        asList("public", "abstract"),
        null,
        Object.class.getTypeName(),
        asList("com.datadog.debugger.symboltest.I1", "com.datadog.debugger.symboltest.I2"),
        null);
    assertEquals(4, classScope.getScopes().size());
    Scope m1MethodScope = classScope.getScopes().get(2);
    assertLangSpecifics(
        m1MethodScope.getLanguageSpecifics(),
        asList("protected", "abstract"),
        null,
        null,
        null,
        Void.TYPE.getTypeName());
    Scope m2MethodScope = classScope.getScopes().get(3);
    assertLangSpecifics(
        m2MethodScope.getLanguageSpecifics(),
        asList("private", "final", "synchronized", "(varargs)", "strictfp"),
        null,
        null,
        null,
        String.class.getTypeName());
    Scope i1ClassScope = symbolSinkMock.jarScopes.get(1).getScopes().get(0);
    assertLangSpecifics(
        i1ClassScope.getLanguageSpecifics(),
        asList("interface", "abstract"),
        null,
        Object.class.getTypeName(),
        null,
        null);
    Scope m3MethodScope = i1ClassScope.getScopes().get(0);
    assertLangSpecifics(
        m3MethodScope.getLanguageSpecifics(),
        asList("public", "default"),
        null,
        null,
        null,
        Void.TYPE.getTypeName());
    Scope m4MethodScope = i1ClassScope.getScopes().get(1);
    assertLangSpecifics(
        m4MethodScope.getLanguageSpecifics(),
        asList("public", "static"),
        null,
        null,
        null,
        String.class.getTypeName());
    Scope myEnumClassScope = symbolSinkMock.jarScopes.get(3).getScopes().get(0);
    assertLangSpecifics(
        myEnumClassScope.getLanguageSpecifics(),
        asList("final", "enum"),
        null,
        Enum.class.getTypeName(),
        null,
        null);
    assertEquals(4, myEnumClassScope.getSymbols().size());
    Symbol oneField = myEnumClassScope.getSymbols().get(0);
    assertLangSpecifics(
        oneField.getLanguageSpecifics(),
        asList("public", "static", "final", "enum"),
        null,
        null,
        null,
        null);
    Symbol valuesField = myEnumClassScope.getSymbols().get(3);
    assertLangSpecifics(
        valuesField.getLanguageSpecifics(),
        asList("private", "static", "final", "synthetic"),
        null,
        null,
        null,
        null);
  }

  @Test
  @EnabledOnJre({JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_24})
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void symbolExtraction15() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction15";
    final String SOURCE_FILE = SYMBOL_PACKAGE_DIR + "SymbolExtraction15.java";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME, "17");
    Reflect.on(testClass).call("main", "1").get();
    Scope classScope = symbolSinkMock.jarScopes.get(0).getScopes().get(0);
    assertScope(classScope, ScopeType.CLASS, CLASS_NAME, 10, 13, SOURCE_FILE, 8, 3);
    assertLangSpecifics(
        classScope.getLanguageSpecifics(),
        asList("public", "final", "record"),
        null,
        "java.lang.Record",
        null,
        null);
    Scope initMethodScope = classScope.getScopes().get(0);
    assertScope(initMethodScope, ScopeType.METHOD, "<init>", 10, 10, SOURCE_FILE, 0, 3);
    assertSymbol(
        initMethodScope.getSymbols().get(0),
        SymbolType.ARG,
        "firstName",
        String.class.getTypeName(),
        10);
    assertSymbol(
        initMethodScope.getSymbols().get(1),
        SymbolType.ARG,
        "lastName",
        String.class.getTypeName(),
        10);
    assertSymbol(
        initMethodScope.getSymbols().get(2), SymbolType.ARG, "age", Integer.TYPE.getTypeName(), 10);
    Scope mainMethodScope = classScope.getScopes().get(1);
    assertScope(mainMethodScope, ScopeType.METHOD, "main", 13, 13, SOURCE_FILE, 0, 1);
    Scope toStringMethodScope = classScope.getScopes().get(2);
    assertScope(toStringMethodScope, ScopeType.METHOD, "toString", 10, 10, SOURCE_FILE, 0, 0);
    Scope hashCodeMethodScope = classScope.getScopes().get(3);
    assertScope(hashCodeMethodScope, ScopeType.METHOD, "hashCode", 10, 10, SOURCE_FILE, 0, 0);
    Scope equalsMethodScope = classScope.getScopes().get(4);
    assertScope(equalsMethodScope, ScopeType.METHOD, "equals", 10, 10, SOURCE_FILE, 0, 1);
    Scope firstNameMethodScope = classScope.getScopes().get(5);
    assertScope(firstNameMethodScope, ScopeType.METHOD, "firstName", 10, 10, SOURCE_FILE, 0, 0);
    Scope lastNameMethodScope = classScope.getScopes().get(6);
    assertScope(lastNameMethodScope, ScopeType.METHOD, "lastName", 10, 10, SOURCE_FILE, 0, 0);
    Scope ageMethodScope = classScope.getScopes().get(7);
    assertScope(ageMethodScope, ScopeType.METHOD, "age", 10, 10, SOURCE_FILE, 0, 0);
  }

  @Test
  public void filterOutClassesFromExcludedPackages() throws IOException, URISyntaxException {
    config = Mockito.mock(Config.class);
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    final String CLASS_NAME = EXCLUDED_PACKAGE + "SymbolExtraction16";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(Collections.singleton(EXCLUDED_PACKAGE));
    SymbolExtractionTransformer transformer =
        new SymbolExtractionTransformer(
            new SymbolAggregator(classNameFiltering, emptyList(), symbolSinkMock, 1),
            classNameFiltering);
    instr.addTransformer(transformer);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Reflect.on(testClass).call("main", "1").get();
    assertFalse(
        symbolSinkMock.jarScopes.stream()
            .flatMap(scope -> scope.getScopes().stream())
            .anyMatch(scope -> scope.getName().equals(CLASS_NAME)));
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void duplicateClassThroughDifferentClassLoader() throws IOException, URISyntaxException {
    final String CLASS_NAME = SYMBOL_PACKAGE + "SymbolExtraction01";
    SymbolSinkMock symbolSinkMock = new SymbolSinkMock(config);
    SymbolExtractionTransformer transformer = createTransformer(symbolSinkMock);
    instr.addTransformer(transformer);
    for (int i = 0; i < 10; i++) {
      // compile and load the class in a specific ClassLoader each time
      Class<?> testClass = compileAndLoadClass(CLASS_NAME);
      Reflect.on(testClass).call("main", "1").get();
    }
    assertEquals(1, symbolSinkMock.jarScopes.size());
  }

  private void assertLangSpecifics(
      LanguageSpecifics languageSpecifics,
      List<String> expectedModifiers,
      List<String> expectedAnnotations,
      String expectedSuperClass,
      List<String> expectedInterfaces,
      String expectedReturnType) {
    if (expectedModifiers == null) {
      assertNull(languageSpecifics.getAccessModifiers());
    } else {
      assertEquals(expectedModifiers, languageSpecifics.getAccessModifiers());
    }
    if (expectedAnnotations == null) {
      assertNull(languageSpecifics.getAnnotations());
    } else {
      assertEquals(expectedAnnotations, languageSpecifics.getAnnotations());
    }
    if (expectedSuperClass == null) {
      assertNull(languageSpecifics.getSuperClass());
    } else {
      assertEquals(expectedSuperClass, languageSpecifics.getSuperClass());
    }
    if (expectedInterfaces == null) {
      assertNull(languageSpecifics.getInterfaces());
    } else {
      assertEquals(expectedInterfaces, languageSpecifics.getInterfaces());
    }
    if (expectedReturnType == null) {
      assertNull(languageSpecifics.getReturnType());
    } else {
      assertEquals(expectedReturnType, languageSpecifics.getReturnType());
    }
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
    if (name != null && name.endsWith("*")) {
      name = name.substring(0, name.length() - 1);
      assertTrue(scope.getName().startsWith(name));
    } else {
      assertEquals(name, scope.getName());
    }
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

  private void assertLineRanges(Scope scope, String... expectedRanges) {
    assertTrue(scope.hasInjectibleLines());
    assertEquals(expectedRanges.length, scope.getInjectibleLines().size());
    int idx = 0;
    for (String expectedRange : expectedRanges) {
      String[] range = expectedRange.split("-");
      assertEquals(Integer.parseInt(range[0]), scope.getInjectibleLines().get(idx).start);
      assertEquals(Integer.parseInt(range[1]), scope.getInjectibleLines().get(idx).end);
      idx++;
    }
  }

  private SymbolExtractionTransformer createTransformer(SymbolSink symbolSink) {
    return createTransformer(symbolSink, 1);
  }

  private SymbolExtractionTransformer createTransformer(
      SymbolSink symbolSink, int symbolFlushThreshold) {
    return createTransformer(
        symbolSink,
        symbolFlushThreshold,
        new ClassNameFiltering(
            TRANSFORMER_EXCLUDES, Collections.singleton(SYMBOL_PACKAGE), Collections.emptySet()));
  }

  private SymbolExtractionTransformer createTransformer(
      SymbolSink symbolSink, int symbolFlushThreshold, ClassNameFiltering classNameFiltering) {
    return new SymbolExtractionTransformer(
        new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, symbolFlushThreshold),
        classNameFiltering);
  }

  static class SymbolSinkMock extends SymbolSink {
    final List<Scope> jarScopes = new ArrayList<>();

    public SymbolSinkMock(Config config) {
      super(config);
    }

    @Override
    public void addScope(Scope jarScope) {
      jarScopes.add(jarScope);
    }
  }
}

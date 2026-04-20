package com.datadog.debugger.symbol;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.debugger.sink.SymbolSink;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.util.Strings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.mockito.ArgumentCaptor;

class SymDBEnablementTest {
  private static final String CONFIG_KEY =
      "datadog/2/LIVE_DEBUGGING_SYMBOL_DB/symDb/d5df9e11566aee56b2c9f8a557680ac53fd3a021141a71275345a90e77f2f2ed";
  private static final byte[] UPlOAD_SYMBOL_TRUE = "{\"upload_symbols\": true}".getBytes();
  private static final byte[] UPlOAD_SYMBOL_FALSE = "{\"upload_symbols\": false}".getBytes();
  private Instrumentation instr = mock(Instrumentation.class);
  private Config config;
  private SymbolSink symbolSink;

  @BeforeEach
  public void before() {
    when(instr.getAllLoadedClasses()).thenReturn(new Class[0]);
    when(instr.isModifiableClass(any())).thenReturn(true);
    config = mock(Config.class);
    when(config.getThirdPartyIncludes()).thenReturn(Collections.singleton("com.datadog.debugger"));
    when(config.isSymbolDatabaseEnabled()).thenReturn(true);
    symbolSink = mock(SymbolSink.class);
  }

  @Test
  public void enableDisableSymDBThroughRC() throws Exception {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(
            instr,
            config,
            new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, 1),
            classNameFiltering);
    symDBEnablement.accept(ParsedConfigKey.parse(CONFIG_KEY), UPlOAD_SYMBOL_TRUE, null);
    waitForUpload(symDBEnablement);
    verify(instr).addTransformer(any(SymbolExtractionTransformer.class));
    symDBEnablement.accept(ParsedConfigKey.parse(CONFIG_KEY), UPlOAD_SYMBOL_FALSE, null);
    verify(instr).removeTransformer(any(SymbolExtractionTransformer.class));
  }

  @Test
  public void removeSymDBConfig() throws Exception {
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(
            instr,
            config,
            new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, 1),
            classNameFiltering);
    symDBEnablement.accept(ParsedConfigKey.parse(CONFIG_KEY), UPlOAD_SYMBOL_TRUE, null);
    waitForUpload(symDBEnablement);
    symDBEnablement.remove(ParsedConfigKey.parse(CONFIG_KEY), null);
    verify(instr).removeTransformer(any(SymbolExtractionTransformer.class));
  }

  @Test
  public void noIncludesFilterOutDatadogClass() {
    when(config.getThirdPartyExcludes()).thenReturn(Collections.emptySet());
    when(config.getThirdPartyIncludes()).thenReturn(Collections.singleton("com.datadog.debugger."));
    ClassNameFiltering classNameFiltering = new ClassNameFiltering(config);
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(
            instr,
            config,
            new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, 1),
            classNameFiltering);
    symDBEnablement.startSymbolExtraction();
    ArgumentCaptor<SymbolExtractionTransformer> captor =
        ArgumentCaptor.forClass(SymbolExtractionTransformer.class);
    verify(instr).addTransformer(captor.capture());
    SymbolExtractionTransformer transformer = captor.getValue();
    ClassNameFilter classNameFilter = transformer.getClassNameFiltering();
    assertTrue(classNameFilter.isExcluded("com.datadog.debugger.test.TestClass"));
    assertFalse(classNameFilter.isExcluded("org.foobar.test.TestClass"));
  }

  @Test
  public void parseLoadedClass() throws ClassNotFoundException, IOException {
    Class<?> testClass = loadSymbolClassFromJar();
    when(instr.getAllLoadedClasses()).thenReturn(new Class[] {testClass});
    when(config.getThirdPartyIncludes())
        .thenReturn(
            Stream.of("com.datadog.debugger.", "org.springframework.samples.")
                .collect(Collectors.toSet()));
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    SymbolAggregator symbolAggregator =
        spy(new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, 1));
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(instr, config, symbolAggregator, classNameFiltering);
    symDBEnablement.startSymbolExtraction();
    verify(instr).addTransformer(any(SymbolExtractionTransformer.class));
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(symbolAggregator, times(2))
        .parseClass(
            any(),
            captor.capture(),
            any(),
            eq(getClass().getResource("/debugger-symbol.jar").getFile()));
    assertEquals(
        "com/datadog/debugger/symbol/SymbolExtraction01.class", captor.getAllValues().get(0));
    assertEquals(
        "BOOT-INF/classes/org/springframework/samples/petclinic/vet/VetController.class",
        captor.getAllValues().get(1));
  }

  @Test
  public void processCorruptedJar() throws ClassNotFoundException, MalformedURLException {
    Class<?> testClass = loadSymbolClassFromJar();
    when(instr.getAllLoadedClasses())
        .thenReturn(new Class[] {SymDBEnablementTest.class, testClass});
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    SymbolAggregator symbolAggregatorMock = mock(SymbolAggregator.class);
    doAnswer(
            invocation -> {
              Path arg = invocation.getArgument(1, Path.class);
              if (arg.toString().endsWith("/debugger-symbol.jar")) {
                return null;
              }
              throw new IOException("Corrupted jar");
            })
        .when(symbolAggregatorMock)
        .scanJar(any(), any(), any(), any());
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(instr, config, symbolAggregatorMock, classNameFiltering);
    symDBEnablement.startSymbolExtraction();
    verify(symbolAggregatorMock, times(2)).scanJar(any(), any(), any(), any());
  }

  private Class<?> loadSymbolClassFromJar() throws MalformedURLException, ClassNotFoundException {
    final String CLASS_NAME = "com.datadog.debugger.symbol.SymbolExtraction01";
    URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
    URL jarUrl = new URL("jar:file:" + jarFileUrl.getFile() + "!/");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(CLASS_NAME);
    return testClass;
  }

  @Test
  public void parseLoadedClassFromDirectory()
      throws ClassNotFoundException, IOException, URISyntaxException {
    URL classFilesUrl = getClass().getResource("/");
    URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {classFilesUrl}, null);
    Class<?> testClass = urlClassLoader.loadClass(getClass().getTypeName());
    when(instr.getAllLoadedClasses()).thenReturn(new Class[] {testClass});
    when(config.getThirdPartyIncludes())
        .thenReturn(
            Stream.of("com.datadog.debugger.", "org.springframework.samples.")
                .collect(Collectors.toSet()));
    ClassNameFiltering classNameFiltering = ClassNameFiltering.allowAll();
    SymbolAggregator symbolAggregator =
        spy(new SymbolAggregator(classNameFiltering, emptyList(), symbolSink, 1));
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(instr, config, symbolAggregator, classNameFiltering);
    symDBEnablement.startSymbolExtraction();
    verify(instr).addTransformer(any(SymbolExtractionTransformer.class));
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(symbolAggregator, atLeastOnce()).parseClass(any(), captor.capture(), any(), anyString());
    // verify that we called parseClass on this test class
    assertTrue(captor.getAllValues().contains(getClass().getSimpleName() + ".class"));
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  public void noDuplicateSymbolExtraction() {
    final String CLASS_NAME_PATH = "com/datadog/debugger/symbol/SymbolExtraction01";
    SymbolSink mockSymbolSink = mock(SymbolSink.class);
    ClassNameFiltering classNameFiltering =
        new ClassNameFiltering(
            Collections.singleton("org.springframework."),
            Collections.singleton("com.datadog.debugger."),
            Collections.emptySet());
    SymbolAggregator symbolAggregator =
        new SymbolAggregator(classNameFiltering, emptyList(), mockSymbolSink, 1);
    SymDBEnablement symDBEnablement =
        new SymDBEnablement(instr, config, symbolAggregator, classNameFiltering);
    doAnswer(
            invocation -> {
              SymbolExtractionTransformer transformer = invocation.getArgument(0);
              JarFile jarFile =
                  new JarFile(getClass().getResource("/debugger-symbol.jar").getFile());
              JarEntry jarEntry = jarFile.getJarEntry(CLASS_NAME_PATH + ".class");
              InputStream inputStream = jarFile.getInputStream(jarEntry);
              byte[] buffer = new byte[4096];
              ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
              int readBytes;
              baos.reset();
              while ((readBytes = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, readBytes);
              }
              transformer.transform(
                  getClass().getClassLoader(), CLASS_NAME_PATH, null, null, baos.toByteArray());
              return null;
            })
        .when(instr)
        .addTransformer(any(SymbolExtractionTransformer.class), eq(true));
    when(instr.getAllLoadedClasses())
        .thenAnswer(
            invocation -> {
              URL jarFileUrl = getClass().getResource("/debugger-symbol.jar");
              URL jarUrl = new URL("jar:file:" + jarFileUrl.getFile() + "!/");
              URLClassLoader urlClassLoader = new URLClassLoader(new URL[] {jarUrl}, null);
              Class<?> testClass = urlClassLoader.loadClass(Strings.getClassName(CLASS_NAME_PATH));
              return new Class[] {testClass};
            });
    symDBEnablement.startSymbolExtraction();

    verify(mockSymbolSink, times(1)).addScope(any());
  }

  private void waitForUpload(SymDBEnablement symDBEnablement) throws InterruptedException {
    int count = 0;
    while (symDBEnablement.getLastUploadTimestamp() == 0) {
      Thread.sleep(1);
      if (count++ > 30 * 1000) {
        throw new RuntimeException("Timeout waiting for symDBEnablement.getLastUploadTimestamp()");
      }
    }
  }
}

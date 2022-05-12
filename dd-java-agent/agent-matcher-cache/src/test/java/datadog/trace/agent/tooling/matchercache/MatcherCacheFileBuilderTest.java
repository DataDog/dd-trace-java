package datadog.trace.agent.tooling.matchercache;

import static org.mockito.Mockito.*;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MatcherCacheFileBuilderTest {
  MatcherCacheFileBuilderParams params = mock(MatcherCacheFileBuilderParams.class);
  ClassFinder classFinder;
  MatcherCacheBuilder matcherCacheBuilder;
  MatcherCacheFileBuilder matcherCacheFileBuilder;

  @BeforeEach
  public void initMatcherCacheFileBuilderWithMocks() {
    params = mock(MatcherCacheFileBuilderParams.class);
    classFinder = mock(ClassFinder.class);
    matcherCacheBuilder = mock(MatcherCacheBuilder.class);
    matcherCacheFileBuilder = new MatcherCacheFileBuilder(classFinder, matcherCacheBuilder);
  }

  @Test
  public void testDoNothingIfInvalidParams() throws IOException {
    when(params.validate()).thenReturn(false);

    matcherCacheFileBuilder.buildMatcherCacheFile(params);

    verify(classFinder, never()).findClassesIn(any(), any());
  }

  @Test
  public void testBuildMatcherCacheFile() throws IOException {
    File dataFile = new File("/tmp/out.bin");
    File reportFile = new File("/tmp/out.csv");
    File jdkClassPath = new File("jdk-class-path");

    ClassCollection.Builder ccb = new ClassCollection.Builder();
    ccb.addClass(new byte[0], "a.b.C", "", "");
    ClassCollection jdkClasses = ccb.buildAndReset();

    ccb.addClass(new byte[0], "dd.Agent", "", "");
    ClassCollection ddAgentClasses = ccb.buildAndReset().withParent(jdkClasses);
    File ddAgentJar = new File("dd-java-trace.jar");
    Map<String, ClassCollection> classPaths = new TreeMap<>();
    ccb.addClass(new byte[0], "app.App", "", "");
    classPaths.put("/app/app.jar", ccb.buildAndReset().withParent(jdkClasses));
    ccb.addClass(new byte[0], "lib.Utils", "", "");
    classPaths.put("/libs/", ccb.buildAndReset().withParent(jdkClasses));

    when(params.validate()).thenReturn(true);
    when(params.getOutputCacheDataFile()).thenReturn(dataFile.toString());
    when(params.getOutputCsvReportFile()).thenReturn(reportFile.toString());
    when(params.getJavaHome()).thenReturn(jdkClassPath.toString());
    when(params.getDDAgentJar()).thenReturn(ddAgentJar);
    when(params.getClassPaths()).thenReturn(classPaths.keySet());
    when(classFinder.findClassesIn(jdkClassPath)).thenReturn(jdkClasses);
    when(classFinder.findClassesIn(ddAgentJar)).thenReturn(ddAgentClasses);
    for (Map.Entry<String, ClassCollection> entry : classPaths.entrySet()) {
      when(classFinder.findClassesIn(new File(entry.getKey()))).thenReturn(entry.getValue());
    }

    matcherCacheFileBuilder.buildMatcherCacheFile(params);

    verify(matcherCacheBuilder, times(1)).fill(eq(jdkClasses));
    verify(matcherCacheBuilder, times(1)).fill(eq(ddAgentClasses));
    for (Map.Entry<String, ClassCollection> entry : classPaths.entrySet()) {
      verify(matcherCacheBuilder, times(1)).fill(eq(entry.getValue()));
    }
    verify(matcherCacheBuilder, times(1)).serializeBinary(dataFile);
  }
}

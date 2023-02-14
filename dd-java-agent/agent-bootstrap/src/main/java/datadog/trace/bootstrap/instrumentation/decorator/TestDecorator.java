package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.ci.InstrumentationBridge;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.ci.CIInfo;
import datadog.trace.bootstrap.instrumentation.ci.CIProviderInfo;
import datadog.trace.bootstrap.instrumentation.ci.CIProviderInfoFactory;
import datadog.trace.bootstrap.instrumentation.ci.CITagsProviderImpl;
import datadog.trace.bootstrap.instrumentation.ci.codeowners.Codeowners;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.info.CILocalGitInfoBuilder;
import datadog.trace.bootstrap.instrumentation.ci.git.info.UserSuppliedGitInfoBuilder;
import datadog.trace.bootstrap.instrumentation.ci.source.MethodLinesResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.SourcePathResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public abstract class TestDecorator extends BaseDecorator {

  public static final String TEST_TYPE = "test";
  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";
  public static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private static final String GIT_FOLDER_NAME = ".git";

  private final boolean isCI;
  private final Map<String, String> ciTags;
  private final Codeowners codeowners;
  private final SourcePathResolver sourcePathResolver;
  private final MethodLinesResolver methodLinesResolver;

  public TestDecorator() {
    CIProviderInfo ciProviderInfo = CIProviderInfoFactory.createCIProviderInfo();
    CILocalGitInfoBuilder ciLocalGitInfoBuilder = new CILocalGitInfoBuilder();
    UserSuppliedGitInfoBuilder userSuppliedGitInfoBuilder = new UserSuppliedGitInfoBuilder();

    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    String repoRoot = ciInfo.getCiWorkspace();

    GitInfo ciGitInfo = ciProviderInfo.buildCIGitInfo();
    GitInfo localGitInfo = ciLocalGitInfoBuilder.build(repoRoot, GIT_FOLDER_NAME);
    GitInfo userSuppliedGitInfo = userSuppliedGitInfoBuilder.build();

    CITagsProviderImpl ciTagsProvider =
        new CITagsProviderImpl(ciInfo, ciGitInfo, localGitInfo, userSuppliedGitInfo);
    ciTags = ciTagsProvider.getCiTags();

    isCI = ciProviderInfo.isCI();

    codeowners = InstrumentationBridge.createCodeowners(repoRoot);
    sourcePathResolver = InstrumentationBridge.createSourcePathResolver(repoRoot);
    methodLinesResolver = InstrumentationBridge.createMethodLinesResolver();
  }

  TestDecorator(
      boolean isCI,
      Map<String, String> ciTags,
      Codeowners codeowners,
      SourcePathResolver sourcePathResolver,
      MethodLinesResolver methodLinesResolver) {
    this.isCI = isCI;
    this.ciTags = ciTags;
    this.codeowners = codeowners;
    this.sourcePathResolver = sourcePathResolver;
    this.methodLinesResolver = methodLinesResolver;
  }

  public boolean isCI() {
    return isCI;
  }

  public Map<String, String> getCiTags() {
    return ciTags;
  }

  protected abstract String testFramework();

  protected String testType() {
    return TEST_TYPE;
  }

  protected String spanKind() {
    return Tags.SPAN_KIND_TEST;
  }

  protected String runtimeName() {
    return System.getProperty("java.runtime.name");
  }

  protected String runtimeVendor() {
    return System.getProperty("java.vendor");
  }

  protected String runtimeVersion() {
    return System.getProperty("java.version");
  }

  protected String osArch() {
    return System.getProperty("os.arch");
  }

  protected String osPlatform() {
    return System.getProperty("os.name");
  }

  protected String osVersion() {
    return System.getProperty("os.version");
  }

  protected UTF8BytesString origin() {
    return CIAPP_TEST_ORIGIN;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.TEST;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, spanKind());
    span.setTag(Tags.TEST_FRAMEWORK, testFramework());
    span.setTag(Tags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    span.setTag(Tags.RUNTIME_NAME, runtimeName());
    span.setTag(Tags.RUNTIME_VENDOR, runtimeVendor());
    span.setTag(Tags.RUNTIME_VERSION, runtimeVersion());
    span.setTag(Tags.OS_ARCHITECTURE, osArch());
    span.setTag(Tags.OS_PLATFORM, osPlatform());
    span.setTag(Tags.OS_VERSION, osVersion());
    span.setTag(DDTags.ORIGIN_KEY, CIAPP_TEST_ORIGIN);

    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return super.afterStart(span);
  }

  public AgentSpan afterStart(
      final AgentSpan span,
      final String version,
      final Class<?> testClass,
      final Method testMethod) {
    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }
    if (Config.get().isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(span, testClass, testMethod);
    }
    return afterStart(span);
  }

  private void populateSourceDataTags(AgentSpan span, Class<?> testClass, Method testMethod) {
    if (testClass == null) {
      return;
    }

    String sourcePath = sourcePathResolver.getSourcePath(testClass);
    if (sourcePath == null || sourcePath.isEmpty()) {
      return;
    }

    span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);

    if (testMethod != null) {
      MethodLinesResolver.MethodLines testMethodLines = methodLinesResolver.getLines(testMethod);
      if (testMethodLines.isValid()) {
        span.setTag(Tags.TEST_SOURCE_START, testMethodLines.getStartLineNumber());
        span.setTag(Tags.TEST_SOURCE_END, testMethodLines.getFinishLineNumber());
      }
    }

    Collection<String> testCodeOwners = codeowners.getOwners(sourcePath);
    if (testCodeOwners != null) {
      span.setTag(Tags.TEST_CODEOWNERS, toJson(testCodeOwners));
    }
  }

  public List<Method> testMethods(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<Method> testMethods = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testMethods.add(method);
      }
    }
    return testMethods;
  }

  public boolean isTestSpan(final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return spanKind().equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }
}

package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.ci.CIInfo;
import datadog.trace.bootstrap.instrumentation.ci.CIProviderInfo;
import datadog.trace.bootstrap.instrumentation.ci.CIProviderInfoFactory;
import datadog.trace.bootstrap.instrumentation.ci.CITagsProviderImpl;
import datadog.trace.bootstrap.instrumentation.ci.codeowners.Codeowners;
import datadog.trace.bootstrap.instrumentation.ci.codeowners.CodeownersProvider;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.info.CILocalGitInfoBuilder;
import datadog.trace.bootstrap.instrumentation.ci.git.info.UserSuppliedGitInfoBuilder;
import datadog.trace.bootstrap.instrumentation.ci.source.BestEfforSourcePathResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.CompilerAidedSourcePathResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.MethodLinesResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.MethodLinesResolverImpl;
import datadog.trace.bootstrap.instrumentation.ci.source.RepoIndexSourcePathResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.SourcePathResolver;
import java.util.Map;

public class TestDecoratorInitDataFactory {

  private static final String GIT_FOLDER_NAME = ".git";

  public static TestDecorator.TestDecoratorInitData create() {
    CIProviderInfo ciProviderInfo = CIProviderInfoFactory.createCIProviderInfo();
    boolean isCI = ciProviderInfo.isCI();
    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    String repoRoot = ciInfo.getCiWorkspace();

    GitInfo ciGitInfo = ciProviderInfo.buildCIGitInfo();

    CILocalGitInfoBuilder ciLocalGitInfoBuilder = new CILocalGitInfoBuilder();
    GitInfo localGitInfo = ciLocalGitInfoBuilder.build(repoRoot, GIT_FOLDER_NAME);

    UserSuppliedGitInfoBuilder userSuppliedGitInfoBuilder = new UserSuppliedGitInfoBuilder();
    GitInfo userSuppliedGitInfo = userSuppliedGitInfoBuilder.build();

    CITagsProviderImpl ciTagsProvider =
        new CITagsProviderImpl(ciInfo, ciGitInfo, localGitInfo, userSuppliedGitInfo);
    Map<String, String> ciTags = ciTagsProvider.getCiTags();

    CodeownersProvider codeownersProvider = new CodeownersProvider();
    Codeowners codeowners = codeownersProvider.build(repoRoot);

    SourcePathResolver sourcePathResolver =
        new BestEfforSourcePathResolver(
            new CompilerAidedSourcePathResolver(repoRoot),
            new RepoIndexSourcePathResolver(repoRoot));

    MethodLinesResolver methodLinesResolver = new MethodLinesResolverImpl();

    return new TestDecorator.TestDecoratorInitData(
        isCI, ciTags, codeowners, sourcePathResolver, methodLinesResolver);
  }
}

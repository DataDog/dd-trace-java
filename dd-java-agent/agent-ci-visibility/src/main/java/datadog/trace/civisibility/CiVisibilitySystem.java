package datadog.trace.civisibility;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.civisibility.codeowners.CodeownersProvider;
import datadog.trace.civisibility.git.GitInfo;
import datadog.trace.civisibility.git.info.CILocalGitInfoBuilder;
import datadog.trace.civisibility.git.info.UserSuppliedGitInfoBuilder;
import datadog.trace.civisibility.source.BestEfforSourcePathResolver;
import datadog.trace.civisibility.source.CompilerAidedSourcePathResolver;
import datadog.trace.civisibility.source.MethodLinesResolverImpl;
import datadog.trace.civisibility.source.RepoIndexSourcePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  private static final String GIT_FOLDER_NAME = ".git";

  public static void start() {
    final Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    CIProviderInfo ciProviderInfo = CIProviderInfoFactory.createCIProviderInfo();
    InstrumentationBridge.setCi(ciProviderInfo.isCI());

    CIInfo ciInfo = ciProviderInfo.buildCIInfo();
    GitInfo ciGitInfo = ciProviderInfo.buildCIGitInfo();

    String repoRoot = ciInfo.getCiWorkspace();

    CILocalGitInfoBuilder ciLocalGitInfoBuilder = new CILocalGitInfoBuilder();
    GitInfo localGitInfo = ciLocalGitInfoBuilder.build(repoRoot, GIT_FOLDER_NAME);

    UserSuppliedGitInfoBuilder userSuppliedGitInfoBuilder = new UserSuppliedGitInfoBuilder();
    GitInfo userSuppliedGitInfo = userSuppliedGitInfoBuilder.build();

    CITagsProviderImpl ciTagsProvider =
        new CITagsProviderImpl(ciInfo, ciGitInfo, localGitInfo, userSuppliedGitInfo);
    InstrumentationBridge.setCiTags(ciTagsProvider.getCiTags());

    InstrumentationBridge.setMethodLinesResolver(new MethodLinesResolverImpl());

    CodeownersProvider codeownersProvider = new CodeownersProvider();
    InstrumentationBridge.setCodeowners(codeownersProvider.build(repoRoot));

    InstrumentationBridge.setSourcePathResolver(
        new BestEfforSourcePathResolver(
            new CompilerAidedSourcePathResolver(repoRoot),
            new RepoIndexSourcePathResolver(repoRoot)));
  }
}

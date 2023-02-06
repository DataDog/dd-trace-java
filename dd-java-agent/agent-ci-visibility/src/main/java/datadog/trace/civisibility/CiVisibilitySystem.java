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
import java.io.File;
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

    if (repoRoot != null) {
      CodeownersProvider codeownersProvider = new CodeownersProvider();
      InstrumentationBridge.setCodeowners(codeownersProvider.build(repoRoot));

      InstrumentationBridge.setSourcePathResolver(
          new BestEfforSourcePathResolver(
              new CompilerAidedSourcePathResolver(repoRoot),
              new RepoIndexSourcePathResolver(repoRoot)));
    } else {
      InstrumentationBridge.setCodeowners(path -> null);
      InstrumentationBridge.setSourcePathResolver(clazz -> null);
    }

    InstrumentationBridge.setModule(getModulePath(repoRoot));
  }

  private static String getModulePath(String repoRoot) {
    if (repoRoot == null) {
      return null;
    }

    if (!repoRoot.endsWith(File.separator)) {
      repoRoot += File.separator;
    }

    String currentPath =
        firstNonNull(System.getProperty("basedir"), System.getProperty("user.dir"));
    if (currentPath == null || !currentPath.startsWith(repoRoot)) {
      return null;
    }

    return currentPath.substring(repoRoot.length());
  }

  private static String firstNonNull(String... strings) {
    for (String s : strings) {
      if (s != null) {
        return s;
      }
    }
    return null;
  }
}

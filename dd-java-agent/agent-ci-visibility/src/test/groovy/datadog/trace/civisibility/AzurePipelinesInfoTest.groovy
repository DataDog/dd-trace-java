package datadog.trace.civisibility

import datadog.trace.api.civisibility.CIProviderInfo

class AzurePipelinesInfoTest extends CITagsProviderImplTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new AzurePipelinesInfo()
  }

  @Override
  String getProviderName() {
    return AzurePipelinesInfo.AZURE_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(AzurePipelinesInfo.AZURE, "true")
    map.put(AzurePipelinesInfo.AZURE_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(AzurePipelinesInfo.AZURE, "true")
    map.put(AzurePipelinesInfo.AZURE_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(AzurePipelinesInfo.AZURE_BUILD_SOURCEVERSION, "0000000000000000000000000000000000000000")
    return map
  }
}

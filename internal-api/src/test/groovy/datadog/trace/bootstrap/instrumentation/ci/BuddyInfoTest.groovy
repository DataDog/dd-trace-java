package datadog.trace.bootstrap.instrumentation.ci

class BuddyInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new BuddyInfo() {
        @Override
        protected String getGitFolderName() {
          return GIT_FOLDER_FOR_TESTS
        }
      }
  }

  @Override
  String getProviderName() {
    return BuddyInfo.BUDDY_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuddyInfo.BUDDY, "true")
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuddyInfo.BUDDY, "true")
    map.put(BuddyInfo.BUDDY_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}

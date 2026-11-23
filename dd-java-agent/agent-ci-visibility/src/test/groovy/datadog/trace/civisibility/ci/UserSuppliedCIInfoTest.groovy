package datadog.trace.civisibility.ci

import java.nio.file.Path
import spock.lang.TempDir

class UserSuppliedCIInfoTest extends CITagsProviderTest {
  @TempDir Path emptyWorkspace

  @Override
  Path getCiSpecWorkspacePath() {
    return emptyWorkspace
  }


  @Override
  String getProviderName() {
    return "usersupplied"
  }

  boolean isCi() {
    false
  }
}

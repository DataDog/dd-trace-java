package datadog.gradle.plugin.version

import com.github.zafarkhaja.semver.Version
import org.assertj.core.api.Assertions.assertThat
import org.tabletest.junit.TableTest

class BranchFirstVersioningTest {
  // A blank expectedLine means the branch name is not a release line (fromBranch returns null).
  @TableTest(
    """
    scenario                   | branch              | expectedLine
    release line               | release/v1.69.x     | 1.69
    major release line         | release/v2.0.x      | 2.0
    multi-digit line           | release/v10.123.x   | 10.123
    patch branch name          | release/v1.69.1     |
    missing patch placeholder  | release/v1.69       |
    other release-like branch  | release/v1.69.x-ff  |
    nested release-like branch | release/v1.69.x/fix |
    master                     | master              |
    feature branch             | mo/release/v1.69.x  |
    """
  )
  fun parsesReleaseLineFromBranchName(branch: String, expectedLine: String?) {
    assertThat(ReleaseLine.fromBranch(branch)?.toString()).isEqualTo(expectedLine)
  }

  @TableTest(
    """
    scenario                  | refs                                                                            | expectedLine
    no release branch         | []                                                                              |
    ls-remote output          | ['abc123\trefs/heads/release/v1.68.x', 'def456\trefs/heads/release/v1.69.x']    | 1.69
    for-each-ref output       | [refs/remotes/origin/release/v1.69.x, refs/remotes/origin/release/v1.68.x]      | 1.69
    numeric not lexical order | [refs/remotes/origin/release/v1.9.x, refs/remotes/origin/release/v1.10.x]       | 1.10
    major beats minor         | [refs/remotes/origin/release/v1.99.x, refs/remotes/origin/release/v2.0.x]       | 2.0
    unparsable names ignored  | [refs/remotes/origin/release/v1.99.x-test, refs/remotes/origin/release/v1.68.x] | 1.68
    only unparsable names     | [refs/remotes/origin/release/v1.99, refs/remotes/origin/release/vnext]          |
    """
  )
  fun findsNewestReleaseLine(refs: List<String>, expectedLine: String?) {
    val tabSeparatedRefs = refs.map { it.replace("\\t", "\t") }
    assertThat(BranchFirstVersioning.newestReleaseLine(tabSeparatedRefs)?.toString()).isEqualTo(expectedLine)
  }

  @TableTest(
    """
    scenario                          | lastTag | newestReleaseLine | expectedVersion
    no release branch                 | 1.68.0  |                   | 1.69.0
    release branch of last tag line   | 1.68.0  | 1.68              | 1.69.0
    older release branch              | 1.68.2  | 1.67              | 1.69.0
    release branch cut before its tag | 1.68.0  | 1.69              | 1.70.0
    release branch of new major       | 1.99.0  | 2.0               | 2.1.0
    """
  )
  fun computesNextMinorVersion(lastTag: String, newestReleaseLine: String?, expectedVersion: String) {
    val line = newestReleaseLine?.let { ReleaseLine.fromBranch("release/v$it.x") }
    assertThat(BranchFirstVersioning.nextMinorVersion(Version.parse(lastTag), line).toString()).isEqualTo(expectedVersion)
  }

  @TableTest(
    """
    scenario               | commitsSinceCut | qualifier | dirty | useSnapshot | expectedVersion
    at branch cut          | 0               |           | false | true        | 1.69.0-rc.0
    after pin commit       | 1               |           | false | true        | 1.69.0-rc.1
    with qualifier         | 2               | ddprof    | false | true        | 1.69.0-ddprof-rc.2
    blank qualifier        | 2               | ' '       | false | true        | 1.69.0-rc.2
    dirty with snapshot    | 1               |           | true  | true        | 1.69.0-rc.1-DIRTY
    dirty without snapshot | 1               |           | true  | false       | 1.69.0-rc.1-dirty
    qualifier and dirty    | 3               | ddprof    | true  | true        | 1.69.0-ddprof-rc.3-DIRTY
    """
  )
  fun buildsFirstReleaseCandidateVersion(
    commitsSinceCut: Int,
    qualifier: String?,
    dirty: Boolean,
    useSnapshot: Boolean,
    expectedVersion: String,
  ) {
    val version = BranchFirstVersioning.firstReleaseCandidateVersion(
      ReleaseLine(1, 69),
      commitsSinceCut,
      qualifier,
      dirty,
      useSnapshot,
    )
    assertThat(version).isEqualTo(expectedVersion)
  }
}

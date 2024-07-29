package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.civisibility.domain.ModuleLayout;
import datadog.trace.api.civisibility.domain.SourceSet;
import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import javax.annotation.concurrent.GuardedBy;
import org.jetbrains.annotations.Nullable;

// FIXME nikita: add Javadoc explaining the purpose of this class

// FIXME nikita: the idea is to store all covered lines for all tests (not on a per-test basis, but
// just like a single file->bitmap structure)
// FIXME nikita: at the end of a session/module what we'll need is just the total count of covered
// lines (retrieved from that same data structure)
//  and the total count of executable lines (retrieved from Jacoco or, alternatively, during
// repository indexing (by ASM-parsing every single .class file)

// FIXME nikita: the problem is excluding the test files (because Jacoco/users do not care about the
// coverage of the Tests Code)

// FIXME nikita: this should have "skipped covered lines" pre-populated from what is received from
// the backend
public class ItrCoverageCalculator implements CoverageCalculator {

  private final Object coverageDataLock = new Object();

  // FIXME nikita: consider using a Trie
  @GuardedBy("coverageDataLock")
  private final Collection<String> sourceDirs = new HashSet<>();

  private void addModuleLayout(ModuleLayout moduleLayout) {
    synchronized (coverageDataLock) {
      for (SourceSet sourceSet : moduleLayout.getSourceSets()) {
        if (sourceSet.getType() == SourceSet.Type.TEST) {
          // test sources should not be considered when calculating code coverage percentage
          continue;
        }
        for (File source : sourceSet.getSources()) {
          sourceDirs.add(source.toString());
        }
      }
    }
  }

  @Nullable
  @Override
  public Long calculateCoveragePercentage() {
    return 0L;
  }
}

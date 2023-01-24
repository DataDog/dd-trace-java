package datadog.trace.bootstrap.instrumentation.ci.git.pack;

import java.io.File;

public abstract class VersionedPackGitInfoExtractor {

  public static final byte TYPE_INDEX = 0;
  public static final byte SIZE_INDEX = 1;

  public abstract short getVersion();

  public abstract GitPackObject extract(
      final File idxFile, final File packFile, final String commitSha);
}

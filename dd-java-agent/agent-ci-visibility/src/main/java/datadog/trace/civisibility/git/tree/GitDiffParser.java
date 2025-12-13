package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.diff.LineDiff;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitDiffParser {

  private static final Pattern CHANGED_FILE_PATTERN =
      Pattern.compile("^diff --git (?<oldfilename>.+) (?<newfilename>.+)$");
  private static final Pattern CHANGED_LINES_PATTERN =
      Pattern.compile("^@@ -\\d+(,\\d+)? \\+(?<startline>\\d+)(,(?<count>\\d+))? @@");

  public static @NonNull LineDiff parse(InputStream input) throws IOException {
    Map<String, BitSet> linesByRelativePath = new HashMap<>();

    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()));
    String changedFile = null;
    BitSet changedLines = null;

    String line;
    while ((line = bufferedReader.readLine()) != null) {
      Matcher changedFileMatcher = CHANGED_FILE_PATTERN.matcher(line);
      if (changedFileMatcher.matches()) {
        if (changedFile != null) {
          linesByRelativePath.put(changedFile, changedLines);
        }
        changedFile = changedFileMatcher.group("newfilename");
        changedLines = new BitSet();

      } else {
        Matcher changedLinesMatcher = CHANGED_LINES_PATTERN.matcher(line);
        while (changedLinesMatcher.find()) {
          int startLine = Integer.parseInt(changedLinesMatcher.group("startline"));
          String stringCount = changedLinesMatcher.group("count");
          int count = stringCount != null ? Integer.parseInt(stringCount) : 1;
          if (changedLines == null) {
            throw new IllegalStateException(
                "Line "
                    + line
                    + " contains changed lines information, but no changed file info is available");
          }
          changedLines.set(startLine, startLine + count);
        }
      }
    }

    if (changedFile != null) {
      linesByRelativePath.put(changedFile, changedLines);
    }

    return new LineDiff(linesByRelativePath);
  }
}

package datadog.trace.civisibility.git.tree;

import static datadog.trace.api.git.GitUtils.getFilesystemCharset;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.civisibility.diff.LineDiff;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

public class GitDiffParser {

  // ISO-8859-1 maps byte 0x85 to U+0085, which regex dot excludes without DOTALL.
  // Rejecting that header would leave its hunks attached to the preceding file.
  private static final Pattern CHANGED_FILE_PATTERN =
      Pattern.compile("^diff --git (?<oldfilename>.+) (?<newfilename>.+)$", Pattern.DOTALL);
  private static final Pattern CHANGED_LINES_PATTERN =
      Pattern.compile("^@@ -\\d+(,\\d+)? \\+(?<startline>\\d+)(,(?<count>\\d+))? @@");

  public static @Nonnull LineDiff parse(InputStream input) throws IOException {
    Map<String, BitSet> linesByRelativePath = new HashMap<>();

    // Preserve pathname bytes while skipping file contents, whose charset may differ.
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(input, ISO_8859_1));
    String changedFile = null;
    BitSet changedLines = null;

    String line;
    while ((line = bufferedReader.readLine()) != null) {
      Matcher changedFileMatcher = CHANGED_FILE_PATTERN.matcher(line);
      if (changedFileMatcher.matches()) {
        if (changedFile != null) {
          linesByRelativePath.put(changedFile, changedLines);
        }
        changedFile = decodeFilename(changedFileMatcher.group("newfilename"));
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

  private static String decodeFilename(String filename) {
    byte[] bytes = filename.getBytes(ISO_8859_1);
    Charset filesystemCharset = getFilesystemCharset();
    try {
      // Match the repository index's filesystem interpretation. Valid EUC-JP bytes can also be
      // valid UTF-8 with a different pathname.
      return filesystemCharset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      try {
        // Git can emit UTF-8 pathname bytes that an ASCII filesystem charset rejects.
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException ignored) {
        // Retain filesystem replacement behaviour when neither decoder accepts the name.
        return new String(bytes, filesystemCharset);
      }
    }
  }
}

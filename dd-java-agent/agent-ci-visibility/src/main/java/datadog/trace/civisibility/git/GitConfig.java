package datadog.trace.civisibility.git;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a .git/config file. It uses a simple algorithm based on regex to parse line by line
 * the .git/config file (INI file format).
 */
public class GitConfig {

  private final Map<String, Map<String, String>> entries = new HashMap<>();

  public GitConfig(final String path) {
    load(path);
  }

  @SuppressForbidden // split with one-char String use a fast-path without regex usage
  private void load(final String path) {
    if (path == null || path.isEmpty()) {
      return;
    }

    // Typically, a section of the .git/config file looks like:
    // [remote "origin"]
    //   url = https://some-host/user/repository.git
    // 	 fetch = +refs/heads/*:refs/remotes/origin/*

    try (final BufferedReader br = new BufferedReader(new FileReader(path))) {
      String line;
      String section = null;
      while ((line = br.readLine()) != null) {

        // Check if current line matches with the `section` regex:
        int sectionStartIdx = line.indexOf('[');
        if (sectionStartIdx >= 0) {
          int sectionEndIdx = line.indexOf(']', sectionStartIdx + 1);
          if (sectionEndIdx >= 0
              && isWhitespace(line, 0, sectionStartIdx)
              && isWhitespace(line, sectionEndIdx + 1, line.length())) {
            // Section found: (E.g: remote "origin")
            section = line.substring(sectionStartIdx + 1, sectionEndIdx);
            continue;
          }
        }

        if (section != null) {
          // Locate the concrete `section` in the `entries` map
          // and update it with the found key/value.
          // E.g: Map({`remote "origin"`: {`url`:`https://some-host/user/repository.git`}}
          Map<String, String> sectionValues =
              this.entries.computeIfAbsent(section, k -> new HashMap<>());

          String[] parts = line.split("=");
          if (parts.length >= 2) {
            // Key/value found: (E.g: key=url, value=https://some-host/user/repository.git)
            final String key = parts[0].trim();
            final String value = join(parts, 1, parts.length).trim();
            sectionValues.put(key, value);
          }
        }
      }
    } catch (final IOException e) {
      // As extract .git config information should be a best-effort approach, we don't want to
      // bother customers with
      // error messages at this point. If .git/config file cannot be parsed, we return the control
      // to the invoker.
    }
  }

  private String join(String[] array, int from, int to) {
    if (to - from == 1) {
      return array[from];
    } else {
      StringBuilder joined = new StringBuilder();
      for (int i = from; i < to; i++) {
        joined.append(array[i]);
      }
      return joined.toString();
    }
  }

  private boolean isWhitespace(String s, int from, int to) {
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public String getString(final String section, final String key) {
    final Map<String, String> kv = entries.get(section);
    if (kv == null) {
      return null;
    }
    return kv.get(key);
  }
}

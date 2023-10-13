package datadog.trace.civisibility.git;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a .git/config file. It uses a simple algorithm based on regex to parse line by line
 * the .git/config file (INI file format).
 */
public class GitConfig {

  private final Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");
  private final Pattern keyValue = Pattern.compile("\\s*([^=]*)=(.*)");
  private final Map<String, Map<String, String>> entries = new HashMap<>();

  public GitConfig(final String path) {
    load(path);
  }

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
        Matcher m = this.section.matcher(line);
        if (m.matches()) {
          // Section found: (E.g: remote "origin")
          section = m.group(1).trim();
        } else if (section != null) {
          // Locate the concrete `section` in the `entries` map
          // and update it with the found key/value.
          // E.g: Map({`remote "origin"`: {`url`:`https://some-host/user/repository.git`}}
          Map<String, String> kv = this.entries.get(section);
          if (kv == null) {
            this.entries.put(section, kv = new HashMap<>());
          }
          // Check if current line is a key/value inside of a certain section.
          m = this.keyValue.matcher(line);
          if (m.matches()) {
            // Key/value found: (E.g: key=url, value=https://some-host/user/repository.git)
            final String key = m.group(1).trim();
            final String value = m.group(2).trim();
            kv.put(key, value);
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

  public String getString(final String section, final String key) {
    final Map<String, String> kv = entries.get(section);
    if (kv == null) {
      return null;
    }
    return kv.get(key);
  }
}

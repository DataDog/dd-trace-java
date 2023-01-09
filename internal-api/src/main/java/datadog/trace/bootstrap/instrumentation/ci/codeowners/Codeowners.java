package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import datadog.trace.bootstrap.instrumentation.ci.codeowners.matcher.CharacterMatcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import javax.annotation.Nullable;

/**
 * @see <a
 *     href="https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners">CODEOWNERS
 *     file description</a>
 */
public class Codeowners {

  public static final Codeowners EMPTY = new Codeowners("", Collections.emptyList());

  private final String repoRoot;
  private final Iterable<Entry> entries;

  private Codeowners(String repoRoot, Iterable<Entry> entries) {
    this.repoRoot = repoRoot + (repoRoot.endsWith("/") ? "" : "/");
    this.entries = entries;
  }

  /**
   * @param path <b>Absolute</b> path to a file/folder inside the repository
   * @return the list of teams/people who own the provided path
   */
  public @Nullable Collection<String> getOwners(String path) {
    if (!path.startsWith(repoRoot)) {
      return null;
    }

    char[] relativePath = new char[path.length() - repoRoot.length()];
    path.getChars(repoRoot.length(), path.length(), relativePath, 0);

    for (Entry entry : entries) {
      if (entry.getMatcher().consume(relativePath, 0) >= 0) {
        return entry.getOwners();
      }
    }
    return null;
  }

  public static Codeowners parse(String repoRoot, Reader r) throws IOException {
    Deque<Entry> entries = new ArrayDeque<>();

    CharacterMatcher.Factory characterMatcherFactory = new CharacterMatcher.Factory();
    BufferedReader br = new BufferedReader(r);
    String s;
    while ((s = br.readLine()) != null) {
      EntryBuilder entryBuilder = new EntryBuilder(characterMatcherFactory, s);
      Entry entry = entryBuilder.parse();
      if (entry != null) {
        // place last parsed entry in the beginning of the list, since it has the highest priority
        entries.offerFirst(entry);
      }
    }

    return new Codeowners(repoRoot, entries);
  }
}

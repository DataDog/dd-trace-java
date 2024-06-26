package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CodeownersImpl implements Codeowners {

  private final Iterable<Entry> entries;

  private CodeownersImpl(Iterable<Entry> entries) {
    this.entries = entries;
  }

  /**
   * @param path path to a file/folder relative to the repository root
   * @return the list of teams/people who own the provided path
   */
  @Override
  public @Nullable Collection<String> getOwners(@Nonnull String path) {
    char[] pathCharacters = path.toCharArray();
    for (Entry entry : entries) {
      if (entry.getMatcher().consume(pathCharacters, 0) >= 0) {
        return entry.getOwners();
      }
    }
    return null;
  }

  @Override
  public boolean exist() {
    return true;
  }

  public static Codeowners parse(Reader r) throws IOException {
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

    return new CodeownersImpl(entries);
  }
}

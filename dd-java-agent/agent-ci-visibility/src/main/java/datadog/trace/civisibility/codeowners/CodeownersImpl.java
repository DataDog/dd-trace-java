package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CodeownersImpl implements Codeowners {

  // Entries grouped by section in order of appearance (the unnamed section that precedes the first
  // header comes first). Within a section, the highest-priority (last in the file) matching entry
  // wins and owners from every matching section are then combined.
  private final Collection<Deque<Entry>> sections;

  private CodeownersImpl(Collection<Deque<Entry>> sections) {
    this.sections = sections;
  }

  /**
   * @param path path to a file/folder relative to the repository root
   * @return the list of teams/people who own the provided path
   */
  @Override
  public @Nullable Collection<String> getOwners(@Nonnull String path) {
    char[] pathCharacters = path.toCharArray();
    Set<String> owners = null;
    for (Deque<Entry> section : sections) {
      for (Entry entry : section) {
        if (entry.getMatcher().consume(pathCharacters, 0) >= 0) {
          if (owners == null) {
            owners = new LinkedHashSet<>();
          }
          owners.addAll(entry.getOwners());
          break; // highest-priority match within a section wins
        }
      }
    }
    return owners != null ? new ArrayList<>(owners) : null;
  }

  @Override
  public boolean exist() {
    return true;
  }

  public static Codeowners parse(Reader r) throws IOException {
    Deque<Entry> defaultSection = new ArrayDeque<>();
    Map<String, Deque<Entry>> namedSections = new LinkedHashMap<>();
    Deque<Entry> currentSection = defaultSection;

    CharacterMatcher.Factory characterMatcherFactory = new CharacterMatcher.Factory();
    BufferedReader br = new BufferedReader(r);
    String line;
    // Entries without owners inherit the current section's default owners
    Collection<String> sectionDefaultOwners = Collections.emptyList();
    while ((line = br.readLine()) != null) {
      EntryBuilder entryBuilder = new EntryBuilder(characterMatcherFactory, line);

      SectionHeader header = entryBuilder.parseSectionHeader();
      if (header != null) {
        sectionDefaultOwners = header.getDefaultOwners();
        String key = header.getName().trim().toLowerCase(Locale.ROOT);
        currentSection = namedSections.computeIfAbsent(key, k -> new ArrayDeque<>());
        continue;
      }

      Entry entry = entryBuilder.parse(sectionDefaultOwners);
      if (entry != null) {
        // within a section, the last entry in the file has the highest priority
        currentSection.offerFirst(entry);
      }
    }

    // Unnamed section is evaluated first, then named sections in order of first appearance
    List<Deque<Entry>> sections = new ArrayList<>(namedSections.size() + 1);
    sections.add(defaultSection);
    sections.addAll(namedSections.values());
    return new CodeownersImpl(sections);
  }
}

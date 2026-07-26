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

  private final Collection<Section> sections;

  private CodeownersImpl(Collection<Section> sections) {
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
    for (Section section : sections) {
      if (section.isExcluded(pathCharacters)) {
        if (owners == null) {
          owners = new LinkedHashSet<>();
        }
        continue;
      }

      Entry entry = section.findMatchingEntry(pathCharacters);
      if (entry != null) {
        if (owners == null) {
          owners = new LinkedHashSet<>();
        }
        owners.addAll(entry.getOwners());
      }
    }
    return owners != null ? new ArrayList<>(owners) : null;
  }

  @Override
  public boolean exist() {
    return true;
  }

  public static Codeowners parse(Reader r) throws IOException {
    Section defaultSection = new Section();
    Map<String, Section> namedSections = new LinkedHashMap<>();
    Section currentSection = defaultSection;

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
        currentSection = namedSections.computeIfAbsent(key, k -> new Section());
        continue;
      }

      Entry entry = entryBuilder.parse(sectionDefaultOwners);
      if (entry != null) {
        currentSection.add(entry);
      }
    }

    List<Section> sections = new ArrayList<>(namedSections.size() + 1);
    sections.add(defaultSection);
    sections.addAll(namedSections.values());
    return new CodeownersImpl(sections);
  }

  private static final class Section {

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Collection<Entry> exclusions = new ArrayList<>();

    private void add(Entry entry) {
      if (entry.isExclusion()) {
        exclusions.add(entry);
      } else {
        entries.offerFirst(entry);
      }
    }

    private boolean isExcluded(char[] path) {
      for (Entry exclusion : exclusions) {
        if (exclusion.getMatcher().consume(path, 0) >= 0) {
          return true;
        }
      }
      return false;
    }

    private @Nullable Entry findMatchingEntry(char[] path) {
      for (Entry entry : entries) {
        if (entry.getMatcher().consume(path, 0) >= 0) {
          return entry;
        }
      }
      return null;
    }
  }
}

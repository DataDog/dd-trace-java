package datadog.trace.civisibility.codeowners.matcher;

import java.util.HashMap;
import java.util.Map;

public class CharacterMatcher implements Matcher {

  private final char character;

  private CharacterMatcher(char character) {
    this.character = character;
  }

  @Override
  public int consume(char[] line, int offset) {
    return offset < line.length && line[offset] == character ? 1 : -1;
  }

  @Override
  public boolean multi() {
    return false;
  }

  public static final class Factory {
    private final Map<Character, CharacterMatcher> canonical = new HashMap<>();

    public Matcher create(char c) {
      return canonical.computeIfAbsent(c, CharacterMatcher::new);
    }
  }
}

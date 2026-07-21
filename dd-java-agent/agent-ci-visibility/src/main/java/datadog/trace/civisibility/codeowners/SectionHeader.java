package datadog.trace.civisibility.codeowners;

import java.util.Collection;

/**
 * A parsed GitLab CODEOWNERS section header, holding the section {@code name} (used to combine
 * blocks that repeat a name) and the {@code defaultOwners} it declares (inherited by entries in the
 * section that do not declare their own).
 */
public class SectionHeader {

  private final String name;
  private final Collection<String> defaultOwners;

  public SectionHeader(String name, Collection<String> defaultOwners) {
    this.name = name;
    this.defaultOwners = defaultOwners;
  }

  public String getName() {
    return name;
  }

  public Collection<String> getDefaultOwners() {
    return defaultOwners;
  }
}

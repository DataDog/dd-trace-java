package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.Matcher;
import java.util.Collection;

public class Entry {

  private final Matcher matcher;
  private final Collection<String> owners;
  private final boolean exclusion;

  public Entry(Matcher matcher, Collection<String> owners, boolean exclusion) {
    this.matcher = matcher;
    this.owners = owners;
    this.exclusion = exclusion;
  }

  public Matcher getMatcher() {
    return matcher;
  }

  public Collection<String> getOwners() {
    return owners;
  }

  public boolean isExclusion() {
    return exclusion;
  }
}

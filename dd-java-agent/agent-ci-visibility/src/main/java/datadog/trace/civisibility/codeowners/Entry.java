package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.Matcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import javax.annotation.Nullable;

public class Entry {

  private final Matcher matcher;
  private final Collection<String> owners;
  private final boolean exclusion;
  private final @Nullable String indexKey;

  public Entry(
      Matcher matcher, Collection<String> owners, boolean exclusion, @Nullable String indexKey) {
    this.matcher = matcher;
    this.owners = owners.size() > 1 ? new ArrayList<>(new LinkedHashSet<>(owners)) : owners;
    this.exclusion = exclusion;
    this.indexKey = indexKey;
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

  public @Nullable String getIndexKey() {
    return indexKey;
  }
}

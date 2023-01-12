package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import datadog.trace.bootstrap.instrumentation.ci.codeowners.matcher.Matcher;
import java.util.Collection;

public class Entry {

  private final Matcher matcher;
  private final Collection<String> owners;

  public Entry(Matcher matcher, Collection<String> owners) {
    this.matcher = matcher;
    this.owners = owners;
  }

  public Matcher getMatcher() {
    return matcher;
  }

  public Collection<String> getOwners() {
    return owners;
  }
}

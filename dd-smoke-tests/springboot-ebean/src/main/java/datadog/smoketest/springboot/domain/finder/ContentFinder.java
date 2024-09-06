package datadog.smoketest.springboot.domain.finder;

import datadog.smoketest.springboot.domain.Content;
import io.ebean.Finder;

public class ContentFinder extends Finder<Long, Content> {

  /** Construct using the default EbeanServer. */
  public ContentFinder() {
    super(Content.class);
  }
}

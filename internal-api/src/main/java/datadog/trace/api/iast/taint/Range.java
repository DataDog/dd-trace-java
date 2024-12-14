package datadog.trace.api.iast.taint;

import datadog.trace.api.iast.util.Ranged;
import javax.annotation.Nonnull;

public interface Range extends Ranged {

  @Nonnull
  Source getSource();

  int getMarks();

  Range shift(final int offset);

  Range consolidate();

  boolean isMarked(int mark);

  boolean isValid();
}

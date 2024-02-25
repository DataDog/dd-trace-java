package datadog.trace.civisibility.codeowners;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NoCodeowners implements Codeowners {

  public static final Codeowners INSTANCE = new NoCodeowners();

  private NoCodeowners() {}

  @Nullable
  @Override
  public Collection<String> getOwners(@Nonnull String path) {
    return null;
  }

  @Override
  public boolean exist() {
    return false;
  }
}

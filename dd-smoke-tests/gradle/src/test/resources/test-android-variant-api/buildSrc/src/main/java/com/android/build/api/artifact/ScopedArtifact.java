package com.android.build.api.artifact;

/** Test stand-in for the AGP classes artifact that AndroidGradleUtils loads reflectively. */
public abstract class ScopedArtifact {

  public static final class CLASSES {
    public static final CLASSES INSTANCE = new CLASSES();

    private CLASSES() {}
  }
}

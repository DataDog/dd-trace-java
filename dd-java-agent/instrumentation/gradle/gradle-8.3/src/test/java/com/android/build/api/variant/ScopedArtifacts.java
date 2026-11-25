package com.android.build.api.variant;

/** Test stand-in for the AGP scope used to select only the current project's classes. */
public interface ScopedArtifacts {

  enum Scope {
    PROJECT,
    ALL
  }
}

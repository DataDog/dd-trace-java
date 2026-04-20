package datadog.trace.instrumentation.play25.appsec;

import datadog.trace.agent.tooling.muzzle.Reference;

public class MuzzleReferences {

  public static final Reference[] PLAY_25_PLUS = new Reference[] {};

  public static final Reference[] PLAY_25_ONLY =
      new Reference[] {
        new Reference.Builder("play.libs.concurrent.Futures").build(),
        new Reference.Builder("play.Routes").build()
      };
}

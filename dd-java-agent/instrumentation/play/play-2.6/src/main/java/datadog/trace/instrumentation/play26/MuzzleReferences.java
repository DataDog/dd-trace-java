package datadog.trace.instrumentation.play26;

import datadog.trace.agent.tooling.muzzle.Reference;

public class MuzzleReferences {
  private MuzzleReferences() {}

  public static final Reference[] PLAY_26_PLUS =
      new Reference[] {new Reference.Builder("play.components.BodyParserComponents").build()};

  public static final Reference[] PLAY_26_ONLY =
      new Reference[] {
        new Reference.Builder("play.components.BodyParserComponents").build(),
        new Reference.Builder("play.Configuration").build()
      };
}

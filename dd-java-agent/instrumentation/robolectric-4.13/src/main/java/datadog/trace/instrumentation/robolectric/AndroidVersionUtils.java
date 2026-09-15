package datadog.trace.instrumentation.robolectric;

public final class AndroidVersionUtils {

  private static final String[] CODENAMES = {
    null,
    "BASE",
    "BASE_1_1",
    "CUPCAKE",
    "DONUT",
    "ECLAIR",
    "ECLAIR_0_1",
    "ECLAIR_MR1",
    "FROYO",
    "GINGERBREAD",
    "GINGERBREAD_MR1",
    "HONEYCOMB",
    "HONEYCOMB_MR1",
    "HONEYCOMB_MR2",
    "ICE_CREAM_SANDWICH",
    "ICE_CREAM_SANDWICH_MR1",
    "JELLY_BEAN",
    "JELLY_BEAN_MR1",
    "JELLY_BEAN_MR2",
    "KITKAT",
    "KITKAT_WATCH",
    "LOLLIPOP",
    "LOLLIPOP_MR1",
    "M",
    "N",
    "N_MR1",
    "O",
    "O_MR1",
    "P",
    "Q",
    "R",
    "S",
    "S_V2",
    "TIRAMISU",
    "UPSIDE_DOWN_CAKE",
    "VANILLA_ICE_CREAM",
    "BAKLAVA",
    "CINNAMON_BUN"
  };

  private AndroidVersionUtils() {}

  public static String codename(int apiLevel) {
    return apiLevel > 0 && apiLevel < CODENAMES.length ? CODENAMES[apiLevel] : null;
  }
}

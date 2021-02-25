package datadog.trace.core.sqreen;

import io.sqreen.powerwaf.Powerwaf;
import io.sqreen.powerwaf.PowerwafContext;
import io.sqreen.powerwaf.exception.AbstractPowerwafException;
import io.sqreen.powerwaf.exception.TimeoutPowerwafException;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class PowerwafCallback implements Closeable, DataSubscription, DataAvailableListener {

  public static final int MAX_DEPTH = 10;
  public static final int MAX_ELEMENTS = 150;
  public static final int MAX_STRING_SIZE = 4096;
  private static final BindingAccessorLimits BA_LIMITS =
    new BindingAccessorLimits(MAX_DEPTH, MAX_ELEMENTS, MAX_STRING_SIZE);

  private final EngineRule rule;
  private final PowerwafContext ctx;
  private final long maxRunBudgetUs;

  static final String ARACHNI_ATOM = "{" +
    "    \"manifest\": {" +
    "    \"http.url\": {" +
    "      \"inherit_from\": \"http.url\"," +
    "        \"run_on_value\": true," +
    "        \"run_on_key\": true" +
    "    }" +
    "  }," +
    "    \"rules\":[" +
    "      {" +
    "        \"rule_id\":\"1\"," +
    "        \"filters\":[" +
    "        {" +
    "          \"operator\":\"@rx\"," +
    "          \"targets\":[" +
    "            \"http.url\"" +
    "          ]," +
    "          \"value\":\"Arachni\"" +
    "        }" +
    "      ]" +
    "    }" +
    "          ]," +
    "    \"flows\":[" +
    "    {" +
    "      \"name\":\"arachni_detection\"," +
    "      \"steps\":[" +
    "      {" +
    "        \"id\":\"start\"," +
    "        \"rule_ids\":[" +
    "        \"1\"" +
    "                  ]," +
    "        \"on_match\":\"exit_block\"" +
    "      }" +
    "              ]" +
    "    }" +
    "          ]" +
    "}";

  public PowerwafCallback(EngineRule rule) {
    this.rule = rule;

    if (!LibSqreenInitialization.ONLINE) {
      log.warn("In-app WAF initialization failed");
      this.ctx = null;
      this.maxRunBudgetUs = 0;
      return;
    }

    this.maxRunBudgetUs = 0;

    String uniqueId = UUID.randomUUID().toString();
    Map<String, String> atoms = new HashMap<>();
    atoms.put(rule.getName(), ARACHNI_ATOM);
    try {
      ctx = Powerwaf.createContext(uniqueId, atoms);
    } catch (AbstractPowerwafException e) {
      throw new UndeclaredThrowableException(e);
    }
  }


  private Powerwaf.Limits createLimits(long generalBudgetUs) {
    return new Powerwaf.Limits(MAX_DEPTH, MAX_ELEMENTS, MAX_STRING_SIZE,
      generalBudgetUs, this.maxRunBudgetUs);
  }


  @Override
  public void close() {
    log.debug("Closing WAF context %s", this.ctx);
    this.ctx.close();
  }

  private static final Address<String> URL_TAG = new Address<>("http.url");

  @Override
  public void dataAvailable(Flow flow, DataSource newData, DataSource allData) {
    String url = newData.getData(URL_TAG);
    if (url == null) {
      return;
    }
    // resolve bas
    Map<String, Object> resolvedBas = new HashMap<>();
    resolvedBas.put(URL_TAG.getKey(), url);

    long remBudgetNs = ((long) Integer.MAX_VALUE) * 1000;;
    long generalBudgetUs = remBudgetNs / 1000;

    Powerwaf.ActionWithData actionWithData;
    try {
      Powerwaf.Limits limits = createLimits(generalBudgetUs);
      log.debug("Running WAF atom with %s", limits);
      actionWithData = ctx.runRule(rule.getName(), resolvedBas, limits);
    } catch (TimeoutPowerwafException timeout) {
      log.debug("Powerwaf timeout for WAF atom %s (likely after PWArgs resolution)");
      return;
    } catch (AbstractPowerwafException e) {
      log.warn("Failed running WAF atom" + rule.getName(), e);
      return;
    }

    if (actionWithData.action == Powerwaf.Action.OK) {
      log.debug("WAF atom " + rule.getName() + " returned OK");
      return;
    }

    if (actionWithData.action == Powerwaf.Action.BLOCK) {
      flow.block(this.rule, new HashMap<String, Object>());
    }
  }

  @Override
  public boolean matches(Set<String> newAddressKeys, DataSource ds) {
    return newAddressKeys.contains(URL_TAG.getKey());
  }

  @Override
  public Set<String> getAllAddressKeys() {
    HashSet<String> strings = new HashSet<>();
    strings.add(URL_TAG.getKey());
    return strings;
  }

  @Override
  public DataAvailableListener getListener() {
    return this;
  }

  @Override
  public int compareTo(DataSubscription o) {
    return 0;
  }

}

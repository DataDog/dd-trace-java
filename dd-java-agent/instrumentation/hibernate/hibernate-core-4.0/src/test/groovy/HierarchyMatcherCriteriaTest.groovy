class HierarchyMatcherCriteriaTest extends CriteriaTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.hibernate.matching.shortcut.enabled", "false")
  }
}

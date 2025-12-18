class HierarchyMatcherProcedureCallTest extends ProcedureCallTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.hibernate.matching.shortcut.enabled", "false")
  }

  @Override
  def storedProcName() {
    return "ANOTHER_TEST_PROC"
  }

  @Override
  def storedProcSQL() {
    return "CREATE PROCEDURE ${storedProcName()}() MODIFIES SQL DATA BEGIN ATOMIC INSERT INTO Value VALUES (999, 'wilma'); END"
  }
}

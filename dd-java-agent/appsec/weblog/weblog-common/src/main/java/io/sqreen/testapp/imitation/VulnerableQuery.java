package io.sqreen.testapp.imitation;

/** A various SQL/Mongo-query vulnerability imitations. */
public final class VulnerableQuery {

  /**
   * Builds the SQL/Mongo-query without any parameter preparation or value escaping. Just plain
   * concatenation.
   *
   * @param sqlQuery a main query
   * @param criteria criteria to append
   * @return built query as a string
   */
  public static String build(String sqlQuery, String... criteria) {
    return sqlQuery + concatenate(criteria);
  }

  private static String concatenate(String[] criteria) {
    StringBuilder sb = new StringBuilder();
    for (String criterion : criteria) {
      sb.append(criterion);
    }
    return sb.toString();
  }

  private VulnerableQuery() {
    /**/
  }
}

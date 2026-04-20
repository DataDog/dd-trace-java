import scalikejdbc._

class ScalikeSqlExecutor {

  def execute(
      driverClass: String,
      jdbcUrl: String,
      user: String,
      password: String,
      query: String
  ): Unit = {
    Class.forName(driverClass)

    ConnectionPool.singleton(jdbcUrl, user, password)

    implicit val session = AutoSession

    SQL(query).execute().apply()
  }
}

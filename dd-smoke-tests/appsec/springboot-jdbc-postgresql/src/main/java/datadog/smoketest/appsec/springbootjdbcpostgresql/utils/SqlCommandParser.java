package datadog.smoketest.appsec.springbootjdbcpostgresql.utils;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

public class SqlCommandParser extends StatementVisitorAdapter {

  private Command command;

  public static Command findCommand(String sqlStr) throws JSQLParserException {
    SqlCommandParser commandParser = new SqlCommandParser();
    return commandParser.findCommand(CCJSqlParserUtil.parse(sqlStr));
  }

  public Command findCommand(Statement statement) {
    command = null;
    statement.accept(this);
    return command;
  }

  @Override
  public void visit(Select select) {
    command = Command.SELECT;
  }

  @Override
  public void visit(Insert insert) {
    command = Command.INSERT;
  }

  @Override
  public void visit(Update update) {
    command = Command.UPDATE;
  }

  public enum Command {
    SELECT,
    UPDATE,
    INSERT
  }
}

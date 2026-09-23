package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * Driving DuckDB over JDBC: loading an extension, running a statement and reading a result set back as plain Java
 * values.
 *
 * <p>An extension is loaded from the extension directory of DuckDB, and installed from the extension repository of
 * DuckDB if it isn't there yet; without network access and without an installed extension, the test that asked for it
 * is skipped rather than failed. {@code ./gradlew :local-s3-integration-test:installDuckDbExtensions} installs the
 * extensions the tests use, which is what CI runs before them so that a download failure cannot turn them into skips.
 */
final class DuckDb {

  private DuckDb() {
  }

  /**
   * Loads an extension into a connection, installing it first if it isn't installed, and aborts the test if neither
   * works.
   */
  static void loadExtension(Connection connection, String extension) throws SQLException {
    try {
      execute(connection, "LOAD " + extension);
    } catch (SQLException notInstalled) {
      try {
        execute(connection, "INSTALL " + extension);
        execute(connection, "LOAD " + extension);
      } catch (SQLException e) {
        connection.close();
        Assumptions.abort("The " + extension + " extension of DuckDB is neither installed nor downloadable: "
            + e.getMessage());
      }
    }
  }

  static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /**
   * Every row of a query, each as the values of its columns in order.
   */
  static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
      int columns = resultSet.getMetaData().getColumnCount();
      List<List<Object>> rows = new ArrayList<>();
      while (resultSet.next()) {
        List<Object> row = new ArrayList<>(columns);
        for (int column = 1; column <= columns; column++) {
          row.add(resultSet.getObject(column));
        }
        rows.add(row);
      }
      return rows;
    }
  }

  /**
   * The first column of every row of a query.
   */
  static <T> List<T> column(Connection connection, String sql) throws SQLException {
    List<T> values = new ArrayList<>();
    for (List<Object> row : rows(connection, sql)) {
      @SuppressWarnings("unchecked")
      T value = (T) row.get(0);
      values.add(value);
    }
    return values;
  }

  /**
   * The single number a query answers, e.g. a count.
   */
  static long single(Connection connection, String sql) throws SQLException {
    List<List<Object>> rows = rows(connection, sql);
    assertEquals(1, rows.size(), sql);
    return ((Number) rows.get(0).get(0)).longValue();
  }

  /**
   * A local path as a string literal of DuckDB, with forward slashes, which DuckDB accepts on Windows as well.
   */
  static String sqlPath(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
  }

}

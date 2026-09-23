package com.robothy.s3.test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * Installs the DuckDB extensions used by the data-tools tests and reports their resolved versions.
 */
public final class DuckDbExtensionInstaller {

  private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("httpfs", "ducklake", "iceberg", "delta");

  private DuckDbExtensionInstaller() {
  }

  public static void main(String[] extensions) throws SQLException {
    if (extensions.length == 0) {
      throw new IllegalArgumentException("At least one DuckDB extension is required");
    }

    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
      DatabaseMetaData metadata = connection.getMetaData();
      System.out.printf("DuckDB engine %s; JDBC driver %s %s%n",
          engineVersion(connection), metadata.getDriverName(), metadata.getDriverVersion());

      for (String extension : extensions) {
        installAndReport(connection, extension);
      }
    }
  }

  private static String engineVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version()")) {
      result.next();
      return result.getString(1);
    }
  }

  private static void installAndReport(Connection connection, String extension) throws SQLException {
    if (!SUPPORTED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("Unsupported DuckDB extension: " + extension);
    }

    try (Statement statement = connection.createStatement()) {
      statement.execute("INSTALL " + extension);
      statement.execute("LOAD " + extension);
    }

    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT extension_version, installed_from, loaded FROM duckdb_extensions() WHERE extension_name = ?")) {
      statement.setString(1, extension);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("DuckDB did not report the installed " + extension + " extension");
        }
        System.out.printf("DuckDB extension %s: version=%s, source=%s, loaded=%s%n",
            extension, result.getString("extension_version"), result.getString("installed_from"),
            result.getBoolean("loaded"));
      }
    }
  }
}

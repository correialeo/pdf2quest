package com.pdf2questao.export;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Gera um dump .sql (schema + dados) do banco SQLite via JDBC puro, sem
 * depender do binario sqlite3 estar instalado na maquina - so precisa do
 * driver que o projeto ja usa para acessar o proprio concursos.db.
 */
@Service
public class DatabaseExportService {

    private final DataSource dataSource;

    public DatabaseExportService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public byte[] exportSqlDump() {
        try (Connection connection = dataSource.getConnection();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
            writer.write("PRAGMA foreign_keys=OFF;\n");
            writer.write("BEGIN TRANSACTION;\n");

            List<TableInfo> tables = listTables(connection);
            for (TableInfo table : tables) {
                writer.write(table.createSql() + ";\n");
            }
            for (TableInfo table : tables) {
                dumpRows(connection, writer, table.name());
            }

            writer.write("COMMIT;\n");
            writer.flush();
            return buffer.toByteArray();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Falha ao exportar o banco de dados", e);
        }
    }

    private List<TableInfo> listTables(Connection connection) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        String sql = "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(new TableInfo(rs.getString("name"), rs.getString("sql")));
            }
        }
        return tables;
    }

    private void dumpRows(Connection connection, Writer writer, String tableName) throws SQLException, IOException {
        String quotedTable = quoteIdentifier(tableName);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quotedTable)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            String columns = IntStream.rangeClosed(1, columnCount)
                    .mapToObj(i -> columnName(meta, i))
                    .map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));

            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                line.append("INSERT INTO ").append(quotedTable).append(" (").append(columns).append(") VALUES (");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        line.append(", ");
                    }
                    line.append(formatValue(rs.getObject(i)));
                }
                line.append(");\n");
                writer.write(line.toString());
            }
        }
    }

    private String columnName(ResultSetMetaData meta, int index) {
        try {
            return meta.getColumnName(index);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.append("'").toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private record TableInfo(String name, String createSql) {
    }
}

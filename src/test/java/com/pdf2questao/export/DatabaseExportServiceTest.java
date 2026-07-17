package com.pdf2questao.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExportServiceTest {

    @Test
    void exportaEReimportaDadosPreservandoConteudoEAspasEscapadas(@TempDir Path tempDir) throws Exception {
        SQLiteDataSource sourceDs = dataSourceFor(tempDir.resolve("origem.db"));
        try (Connection conn = sourceDs.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE pergunta (id INTEGER PRIMARY KEY, texto TEXT, ativo INTEGER)");
            st.execute("INSERT INTO pergunta (id, texto, ativo) VALUES (1, 'It''s a test', 1)");
            st.execute("INSERT INTO pergunta (id, texto, ativo) VALUES (2, NULL, 0)");
        }

        DatabaseExportService service = new DatabaseExportService(sourceDs);
        String sql = new String(service.exportSqlDump(), StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE TABLE pergunta"));
        assertTrue(sql.contains("It''s a test"));

        SQLiteDataSource targetDs = dataSourceFor(tempDir.resolve("destino.db"));
        try (Connection conn = targetDs.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : sql.split(";\n")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }

        try (Connection conn = targetDs.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT texto, ativo FROM pergunta ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("It's a test", rs.getString("texto"));
            assertEquals(1, rs.getInt("ativo"));

            assertTrue(rs.next());
            assertNull(rs.getString("texto"));
            assertEquals(0, rs.getInt("ativo"));

            assertFalse(rs.next());
        }
    }

    private SQLiteDataSource dataSourceFor(Path path) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + path);
        return ds;
    }
}

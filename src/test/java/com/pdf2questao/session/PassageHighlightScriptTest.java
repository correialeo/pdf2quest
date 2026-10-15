package com.pdf2questao.session;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roda o script de grifo do texto base num Chrome sem interface (selecao, DOM e armazenamento de verdade).
 * Pula sozinho se nao houver Chrome/Chromium (variavel CHROME_BIN ou caminhos comuns).
 */
class PassageHighlightScriptTest {

    private static final List<String> CANDIDATES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser");

    private static String chrome() {
        String fromEnv = System.getenv("CHROME_BIN");
        if (fromEnv != null && Files.isExecutable(Path.of(fromEnv))) {
            return fromEnv;
        }
        return CANDIDATES.stream().filter(p -> Files.isExecutable(Path.of(p))).findFirst().orElse(null);
    }

    @Test
    void grifoFuncionaNoNavegador(@TempDir Path tempDir) throws IOException, InterruptedException {
        String chrome = chrome();
        Assumptions.assumeTrue(chrome != null, "Chrome/Chromium nao encontrado");

        Path script = Path.of("src/main/resources/static/js/passage-highlight.js").toAbsolutePath();
        assertTrue(Files.isRegularFile(script));
        String harness = new String(PassageHighlightScriptTest.class
                .getResourceAsStream("/passage-highlight-harness.html").readAllBytes(), StandardCharsets.UTF_8)
                .replace("%SCRIPT%", script.toUri().toString());
        Path page = tempDir.resolve("harness.html");
        Files.writeString(page, harness, StandardCharsets.UTF_8);

        Path out = tempDir.resolve("dom.html");
        // Perfil do Chrome fora do @TempDir: processos auxiliares do Chrome ainda escrevem nele por alguns instantes
        // depois de encerrado, e o JUnit falharia ao apagar a pasta. Removido no fim, sem exigir sucesso.
        Path profile = Files.createTempDirectory("chrome-profile");
        Process process = new ProcessBuilder(chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
                "--allow-file-access-from-files", "--user-data-dir=" + profile,
                "--virtual-time-budget=3000", "--dump-dom", page.toUri().toString())
                .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // O Chrome demora a encerrar depois de imprimir a pagina: le o resultado assim que ele aparece.
        String dom = "";
        try {
            for (int i = 0; i < 300 && !dom.contains("</html>"); i++) {
                Thread.sleep(200);
                dom = Files.readString(out, StandardCharsets.UTF_8);
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            org.springframework.util.FileSystemUtils.deleteRecursively(profile.toFile());
        }

        Matcher title = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(dom);
        assertTrue(title.find(), "a pagina de teste nao carregou: " + dom);
        assertEquals("PASS", title.group(1));
    }
}

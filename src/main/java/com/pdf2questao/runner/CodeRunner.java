package com.pdf2questao.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Roda os blocos de codigo das aulas no servidor, so para VALIDAR na criacao (o aluno roda no navegador): o mesmo
 * runner-core.js com o mesmo Pyodide e sql.js do navegador, num processo Node com tempo-limite. O codigo vem do
 * agente que escreve a aula, nao do aluno.
 */
@Component
public class CodeRunner {

    static final String[] FILES = {"runner-core.js", "runner-node.js", "harness.py", "pyspark_sim.py"};
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path runtimeDir;
    private final String node;
    private final long timeoutSeconds;

    public CodeRunner(@Value("${pdf2questao.runtime-dir:data/runtime}") String runtimeDir,
                      @Value("${pdf2questao.node-path:node}") String node,
                      @Value("${pdf2questao.runner-timeout-seconds:120}") long timeoutSeconds) {
        this.runtimeDir = Path.of(runtimeDir).toAbsolutePath();
        this.node = node;
        this.timeoutSeconds = timeoutSeconds;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String lang, String code, Map<String, String> datasets, String test, String expected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(boolean ok, String output, String error, Boolean testPassed, Boolean matches) {

        /** Rodou sem erro e passou no que foi pedido (teste e/ou saida esperada). */
        public boolean passed() {
            return ok && !Boolean.FALSE.equals(testPassed) && !Boolean.FALSE.equals(matches);
        }
    }

    public Path runtimeDir() {
        return runtimeDir;
    }

    /** Pyodide, sql.js e KaTeX baixados (./instalar-runtimes.sh). */
    public boolean installed() {
        return Files.isRegularFile(runtimeDir.resolve("node_modules/pyodide/package.json"))
                && Files.isRegularFile(runtimeDir.resolve("node_modules/sql.js/package.json"));
    }

    public List<Result> run(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        if (!installed()) {
            throw new IllegalStateException("A aula tem blocos de código, mas o runtime não está instalado: rode "
                    + "./instalar-runtimes.sh na pasta do pdf2questao (baixa Pyodide e sql.js para " + runtimeDir + ").");
        }
        try {
            Path dir = runtimeDir.resolve("runner");
            Files.createDirectories(dir);
            for (String f : FILES) {
                try (InputStream in = new ClassPathResource("static/runner/" + f).getInputStream()) {
                    Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Process p = new ProcessBuilder(node, dir.resolve("runner-node.js").toString(), runtimeDir.toString())
                    .directory(runtimeDir.toFile()).redirectErrorStream(false).start();
            try (OutputStream out = p.getOutputStream()) {
                out.write(JSON.writeValueAsBytes(requests));
            }
            byte[][] streams = new byte[2][];
            Thread errReader = Thread.ofVirtual().start(() -> {
                try {
                    streams[1] = p.getErrorStream().readAllBytes();
                } catch (IOException e) {
                    streams[1] = new byte[0];
                }
            });
            Thread outReader = Thread.ofVirtual().start(() -> {
                try {
                    streams[0] = p.getInputStream().readAllBytes();
                } catch (IOException e) {
                    streams[0] = new byte[0];
                }
            });
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalArgumentException("Os blocos de código passaram de " + timeoutSeconds + " s rodando "
                        + "(laço infinito?). Simplifique os exemplos.");
            }
            outReader.join();
            errReader.join();
            if (p.exitValue() != 0) {
                throw new IllegalStateException("O runner de código falhou: "
                        + new String(streams[1], StandardCharsets.UTF_8).lines().limit(5).toList());
            }
            return JSON.readValue(streams[0], new TypeReference<List<Result>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível rodar o Node (" + node + "): " + e.getMessage()
                    + ". Instale o Node.js 20+ e rode ./instalar-runtimes.sh.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validação do código interrompida.", e);
        }
    }
}

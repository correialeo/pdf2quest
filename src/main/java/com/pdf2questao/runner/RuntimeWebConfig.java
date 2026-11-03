package com.pdf2questao.runner;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

/**
 * Runtimes das aulas interativas (KaTeX, sql.js, Pyodide) servidos da pasta local baixada por instalar-runtimes.sh,
 * e a CSP das telas que rodam codigo: a pagina so conversa com o proprio servidor (connect-src 'self') e o codigo do
 * aluno roda num Web Worker cuja politica nao deixa buscar nada fora daqui.
 */
@Configuration
public class RuntimeWebConfig implements WebMvcConfigurer {

    /** Pagina da aula: os CDNs que o layout ja usa (Bootstrap, fonte Inter) e nada mais; sem rede para scripts. */
    static final String PAGE_CSP = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
            + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://rsms.me; "
            + "font-src 'self' data: https://cdn.jsdelivr.net https://rsms.me; "
            + "img-src 'self' data:; connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";
    /** Worker que roda o codigo: WebAssembly e o Pyodide (que usa eval internamente), so com recursos locais. */
    static final String WORKER_CSP = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'; "
            + "connect-src 'self'";

    private final CodeRunner runner;

    public RuntimeWebConfig(CodeRunner runner) {
        this.runner = runner;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base = runner.runtimeDir().toUri().toString();
        registry.addResourceHandler("/vendor/pyodide/**").addResourceLocations(base + "node_modules/pyodide/");
        registry.addResourceHandler("/vendor/sqljs/**").addResourceLocations(base + "node_modules/sql.js/dist/");
        registry.addResourceHandler("/vendor/katex/**").addResourceLocations(base + "node_modules/katex/dist/");
    }

    @Bean
    public OncePerRequestFilter lessonCspFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                if (path.startsWith("/runner/")) {
                    response.setHeader("Content-Security-Policy", WORKER_CSP);
                } else if (path.startsWith("/aulas/") || path.startsWith("/quizzes/")) {
                    response.setHeader("Content-Security-Policy", PAGE_CSP);
                }
                chain.doFilter(request, response);
            }
        };
    }
}

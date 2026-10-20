package com.pdf2questao.web;

import com.pdf2questao.session.EmptySessionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.NoSuchElementException;

/**
 * Erros que o usuario consegue causar pela tela viram mensagem na pagina de onde ele veio (ou a pagina de erro),
 * em vez da pagina de erro em ingles do Spring.
 */
@ControllerAdvice
public class ErrorAdvice {

    @ExceptionHandler(EmptySessionException.class)
    public String emptySession(EmptySessionException e, HttpServletRequest request) {
        return back(request, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request) {
        return back(request, "O arquivo é grande demais para importar.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView notFound() {
        ModelAndView view = new ModelAndView("error");
        view.addObject("status", 404);
        return view;
    }

    private static String back(HttpServletRequest request, String message) {
        RequestContextUtils.getOutputFlashMap(request).put("globalError", message);
        return "redirect:" + sameSite(request.getHeader("Referer"));
    }

    /** So volta para paginas do proprio sistema (caminho relativo), nunca para outro site. */
    static String sameSite(String referer) {
        if (referer == null) {
            return "/dashboard";
        }
        int path = referer.indexOf('/', referer.indexOf("//") + 2);
        String target = path < 0 ? "/dashboard" : referer.substring(path);
        return target.startsWith("/") && !target.startsWith("//") ? target : "/dashboard";
    }
}

package com.ordermanager.common;

import com.ordermanager.inventory.InsufficientStockException;
import com.ordermanager.order.InvalidTransitionException;
import com.ordermanager.order.UndoNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import java.util.UUID;

/**
 * Turns domain errors into messages the user can act on. HTMX requests get a small alert swapped into
 * {@code #flash}; full-page requests get the error page. Unexpected errors show only a reference id; the
 * details go to the server log.
 */
@ControllerAdvice
public class GlobalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ModelAndView conflict(HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.CONFLICT, "This order changed — reload");
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ModelAndView invalidTransition(InvalidTransitionException ex, HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UndoNotAllowedException.class)
    public ModelAndView undoNotAllowed(UndoNotAllowedException ex, HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ModelAndView insufficientStock(InsufficientStockException ex, HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView notFound(NotFoundException ex, HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView tooLarge(HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.UNPROCESSABLE_ENTITY, "File is larger than 5 MB");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView badRequest(HttpServletRequest req, HttpServletResponse res) {
        return message(req, res, HttpStatus.BAD_REQUEST, "Bad request");
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception ex, HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex; // let Spring Security answer with 403 / login redirect
        }
        if (ex instanceof ErrorResponse er) { // Spring MVC's own 4xx (unknown URL, wrong method, ...)
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return message(req, res, status, status.getReasonPhrase());
        }
        String ref = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error ref={} on {} {}", ref, req.getMethod(), req.getRequestURI(), ex);
        ModelAndView mv = message(req, res, HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Reference: " + ref);
        mv.addObject("ref", ref);
        return mv;
    }

    private static ModelAndView message(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String msg) {
        if (isHtmx(req)) {
            res.setHeader("HX-Retarget", "#flash");
            res.setHeader("HX-Reswap", "innerHTML");
            ModelAndView mv = new ModelAndView("fragments/alert :: flash", status);
            mv.addObject("kind", "error");
            mv.addObject("message", msg);
            return mv;
        }
        ModelAndView mv = new ModelAndView("error", status);
        mv.addObject("status", status.value());
        mv.addObject("message", msg);
        return mv;
    }

    public static boolean isHtmx(HttpServletRequest req) {
        return "true".equals(req.getHeader("HX-Request"));
    }
}

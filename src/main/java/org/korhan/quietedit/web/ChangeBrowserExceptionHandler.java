package org.korhan.quietedit.web;

import org.korhan.quietedit.versioning.DiffUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Renders a refused diff request as a page rather than as Problem Details.
 *
 * <p>The REST endpoint answers the same three reasons with RFC 7807, which is right
 * for a client and useless to a person who followed a stale link. Same status, same
 * message, delivered as the page they were reading.
 *
 * <p>Scoped to {@link ChangeBrowserController} for the reason the other two advices in
 * this project are scoped: a package-wide advice would claim exceptions from
 * controllers added later.
 */
@ControllerAdvice(assignableTypes = ChangeBrowserController.class)
class ChangeBrowserExceptionHandler {

    @ExceptionHandler(DiffUnavailableException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unavailable(DiffUnavailableException e, Model model) {
        model.addAttribute("reason", e.reason().token());
        model.addAttribute("detail", e.getMessage());
        return "no-diff";
    }
}

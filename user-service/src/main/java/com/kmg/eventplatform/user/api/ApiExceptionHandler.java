package com.kmg.eventplatform.user.api;

import com.kmg.eventplatform.user.application.EmailAlreadyUsedException;
import com.kmg.eventplatform.user.application.UserNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Every problem carries a stable machine-readable
 * {@code code} property so that API clients (e.g. other services) can branch on it.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "User not found", ex.getMessage(), "USER_NOT_FOUND");
        problem.setProperty("userId", ex.getUserId());
        return problem;
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    ProblemDetail handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, "Email already used", ex.getMessage(), "EMAIL_ALREADY_USED");
    }

    @ExceptionHandler(EmailClaimMissingException.class)
    ProblemDetail handleEmailClaimMissing(EmailClaimMissingException ex) {
        return problem(HttpStatus.BAD_REQUEST, "E-mail claim missing", ex.getMessage(), "EMAIL_CLAIM_MISSING");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The user was modified concurrently, reload it and retry", "CONCURRENT_MODIFICATION");
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    ProblemDetail handleInvalidSort(InvalidSortPropertyException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid sort property", ex.getMessage(),
                "INVALID_SORT_PROPERTY");
        problem.setProperty("property", ex.getProperty());
        return problem;
    }

    /** Last resort: log the details, never leak them to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred", "INTERNAL_ERROR");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}

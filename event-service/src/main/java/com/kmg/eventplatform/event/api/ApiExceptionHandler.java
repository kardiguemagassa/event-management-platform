package com.kmg.eventplatform.event.api;

import com.kmg.eventplatform.event.application.EventNotFoundException;
import com.kmg.eventplatform.event.application.NotEventOrganizerException;
import com.kmg.eventplatform.event.domain.CapacityBelowReservedException;
import com.kmg.eventplatform.event.domain.EventHasReservationsException;
import com.kmg.eventplatform.event.domain.EventRuleException;
import com.kmg.eventplatform.event.domain.EventStartNotInFutureException;
import com.kmg.eventplatform.event.domain.InvalidEventStateException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
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
 * {@code code} property so that API clients (e.g. registration-service) can branch on it.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private record HttpMapping(HttpStatus status, String title) {
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail handleEventNotFound(EventNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Event not found", ex.getMessage(), "EVENT_NOT_FOUND");
        problem.setProperty("eventId", ex.getEventId());
        return problem;
    }

    @ExceptionHandler(NotEventOrganizerException.class)
    ProblemDetail handleNotEventOrganizer(NotEventOrganizerException ex) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "Not the event organizer", ex.getMessage(),
                "NOT_EVENT_ORGANIZER");
        problem.setProperty("eventId", ex.getEventId());
        return problem;
    }

    /**
     * Exhaustive switch over the sealed hierarchy: adding a new business rule exception
     * breaks compilation here until its HTTP mapping is defined.
     */
    @ExceptionHandler(EventRuleException.class)
    ProblemDetail handleRuleViolation(EventRuleException ex) {
        HttpMapping mapping = switch (ex) {
            case EventStartNotInFutureException ignored -> new HttpMapping(HttpStatus.BAD_REQUEST, "Event start not in future");
            case InvalidEventStateException ignored -> new HttpMapping(HttpStatus.CONFLICT, "Invalid event state");
            case CapacityBelowReservedException ignored -> new HttpMapping(HttpStatus.CONFLICT, "Capacity below reserved seats");
            case EventHasReservationsException ignored -> new HttpMapping(HttpStatus.CONFLICT, "Event has reservations");
        };
        ProblemDetail problem = problem(mapping.status(), mapping.title(), ex.getMessage(), ex.code());
        if (ex.getEventId() != null) {
            problem.setProperty("eventId", ex.getEventId());
        }
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The event was modified concurrently, reload it and retry", "CONCURRENT_MODIFICATION");
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    ProblemDetail handleInvalidSort(InvalidSortPropertyException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid sort property", ex.getMessage(),
                "INVALID_SORT_PROPERTY");
        problem.setProperty("property", ex.getProperty());
        return problem;
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    ProblemDetail handleInvalidQueryParameter(InvalidQueryParameterException ex) {
        return validationFailed(Map.of(ex.getParameter(), ex.getMessage()));
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
        MethodArgumentNotValidException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return handleExceptionInternal(ex, validationFailed(errors), headers, status, request);
    }

    /** A query or path parameter that does not convert to its type (unknown status, malformed date or id). */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
        TypeMismatchException ex, @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        // For a request parameter (MethodArgumentTypeMismatchException), the property name is the parameter name.
        ProblemDetail problem = validationFailed(Map.of(String.valueOf(ex.getPropertyName()), "Invalid value"));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail validationFailed(Map<String, String> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid",
                "VALIDATION_FAILED");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}

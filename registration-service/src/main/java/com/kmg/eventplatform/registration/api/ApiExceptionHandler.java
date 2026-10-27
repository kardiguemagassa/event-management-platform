package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.EventNotFoundException;
import com.kmg.eventplatform.registration.application.EventNotRegistrableException;
import com.kmg.eventplatform.registration.application.RegistrationFailureException;
import com.kmg.eventplatform.registration.application.RegistrationNotFoundException;
import com.kmg.eventplatform.registration.application.UserNotFoundException;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
import com.kmg.eventplatform.registration.domain.RegistrationRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * RFC 9457 errors with a stable machine-readable "code" property.
 * The switches are exhaustive over sealed hierarchies: a new failure type does not compile until it is mapped.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RegistrationFailureException.class)
    ProblemDetail handleFailure(RegistrationFailureException failure) {
        HttpStatus status = switch (failure) {
            case UserNotFoundException notFound -> HttpStatus.NOT_FOUND;
            case EventNotFoundException notFound -> HttpStatus.NOT_FOUND;
            case RegistrationNotFoundException notFound -> HttpStatus.NOT_FOUND;
            case EventFullException conflict -> HttpStatus.CONFLICT;
            case EventNotRegistrableException conflict -> HttpStatus.CONFLICT;
            case DownstreamUnavailableException unavailable -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if (failure instanceof DownstreamUnavailableException) {
            // Internal topology stays in the logs, not in the public response.
            log.warn("Request failed on a dependency: {}", failure.getMessage());
            return problem(status, failure.code(), "A dependent service is temporarily unavailable, please retry later");
        }
        return problem(status, failure.code(), failure.getMessage());
    }

    @ExceptionHandler(RegistrationRuleException.class)
    ProblemDetail handleRule(RegistrationRuleException violation) {
        HttpStatus status = switch (violation) {
            case RegistrationAlreadyCancelledException conflict -> HttpStatus.CONFLICT;
        };
        return problem(status, violation.code(), violation.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException conflict) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The registration was modified concurrently, please reload and retry");
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    ProblemDetail handleInvalidSort(InvalidSortPropertyException invalidSort) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_SORT_PROPERTY", invalidSort.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException invalid,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : invalid.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(),
                    Objects.requireNonNullElse(fieldError.getDefaultMessage(), "is invalid"));
        }
        ProblemDetail body = invalid.getBody();
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(invalid, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}

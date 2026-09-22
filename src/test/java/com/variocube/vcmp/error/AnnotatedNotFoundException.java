package com.variocube.vcmp.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The shape consumers use for deliberate failures without building a ProblemDetail: a plain
 * exception class carrying its status by annotation, exactly as Spring MVC maps it.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AnnotatedNotFoundException extends RuntimeException {
    public AnnotatedNotFoundException(String message) {
        super(message);
    }
}

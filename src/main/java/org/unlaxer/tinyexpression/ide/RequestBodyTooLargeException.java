package org.unlaxer.tinyexpression.ide;

import java.io.IOException;

/**
 * Thrown when an inbound HTTP request body exceeds the configured maximum
 * size. Servlets catch this to return {@code 413 Payload Too Large} instead
 * of accumulating an unbounded body in memory.
 */
class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException() {
        super("Request body too large");
    }
}

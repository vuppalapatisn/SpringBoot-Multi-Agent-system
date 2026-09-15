package io.github.vuppalapatisn.agentic.foundation.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException() {
        super("order not found");
    }
}

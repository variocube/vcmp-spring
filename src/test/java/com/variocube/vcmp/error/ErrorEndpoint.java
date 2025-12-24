package com.variocube.vcmp.error;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.server.VcmpEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@VcmpEndpoint(path = "/errorVcmp")
public class ErrorEndpoint {
    @VcmpListener
    public void throwException(TestMessage testMessage) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad");
    }
}

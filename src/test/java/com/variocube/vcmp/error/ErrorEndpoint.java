package com.variocube.vcmp.error;

import com.variocube.vcmp.VcmpCallback;
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

    @VcmpListener
    public VcmpCallback<Void> failCallback(FailedCallbackMessage message) {
        // Returns a locally-failed (and therefore locally-marked) callback that the framework
        // chains into the outbound NAK — the marker must be stripped at the wire boundary.
        return VcmpCallback.failed();
    }
}

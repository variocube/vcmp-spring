package com.variocube.vcmp.server;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class VcmpReadyGateFilterTest {

    @Test
    void rejectsWebSocketHandshakeBeforeReady() throws Exception {
        val filter = new VcmpReadyGateFilter();
        val response = new MockHttpServletResponse();
        val chain = new MockFilterChain();

        filter.doFilter(webSocketHandshakeRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsWebSocketHandshakeAfterReady() throws Exception {
        val filter = new VcmpReadyGateFilter();
        filter.handleApplicationReady();
        val response = new MockHttpServletResponse();
        val chain = new MockFilterChain();

        filter.doFilter(webSocketHandshakeRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void acceptsPlainHttpRequestBeforeReady() throws Exception {
        val filter = new VcmpReadyGateFilter();
        val request = new MockHttpServletRequest("GET", "/actuator/health");
        val response = new MockHttpServletResponse();
        val chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest webSocketHandshakeRequest() {
        val request = new MockHttpServletRequest("GET", "/api/vcmp");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("Connection", "Upgrade");
        return request;
    }
}

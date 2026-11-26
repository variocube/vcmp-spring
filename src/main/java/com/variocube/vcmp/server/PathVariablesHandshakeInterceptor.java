package com.variocube.vcmp.server;

import com.variocube.vcmp.VcmpHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Map;

/**
 * Copies the URI template variables of the endpoint's path into the session attributes, for a
 * {@link com.variocube.vcmp.VcmpHandshakeAuthenticator}. The WebSocket handler mapping exposes only the matching
 * pattern, so the variables are extracted from the request path here.
 */
class PathVariablesHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Map<String, String> variables = extractVariables(servletRequest.getServletRequest());
            if (variables != null) {
                attributes.put(VcmpHandler.PATH_VARIABLES_ATTRIBUTE, variables);
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractVariables(HttpServletRequest request) {
        Object exposed = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (exposed instanceof Map<?, ?> map) {
            return (Map<String, String>) map;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String patternString)) {
            return null;
        }
        String path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication().value()
                : UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        PathPatternParser parser = PathPatternParser.defaultInstance;
        var info = parser.parse(patternString).matchAndExtract(PathContainer.parsePath(path));
        return info != null ? info.getUriVariables() : null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Exception exception) {
    }
}

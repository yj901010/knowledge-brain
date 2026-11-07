package io.knowledgebrain.gateway;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keep existing domain trace IDs aligned with an agent-created browser root trace. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name="brain.observability.enabled", havingValue="true")
class TelemetryTraceFilter extends OncePerRequestFilter {
    static boolean valid(String value) {
        return value != null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
            && !value.substring(3,35).equals("0".repeat(32))
            && !value.substring(36,52).equals("0".repeat(16));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        var context = Span.current().getSpanContext();
        if (valid(request.getHeader("traceparent")) || !context.isValid()) {
            chain.doFilter(request, response);
            return;
        }
        String parent = "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return name.equalsIgnoreCase("traceparent") ? parent : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("traceparent") ? Collections.enumeration(List.of(parent)) : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new LinkedHashSet<String>();
                var original = super.getHeaderNames();
                if (original != null) names.addAll(Collections.list(original));
                names.add("traceparent");
                return Collections.enumeration(names);
            }
        }, response);
    }
}

package io.knowledgebrain.gateway;

import io.opentelemetry.api.trace.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryTraceFilterTest {
    static final String TRACE="1234567890abcdef1234567890abcdef", SPAN="1234567890abcdef";
    @Test void absentParentUsesRealActiveTraceAndPreservesOtherHeaders() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("Cookie","opaque");
        var span=Span.wrap(SpanContext.create(TRACE,SPAN,TraceFlags.getSampled(),TraceState.getDefault()));
        try(var scope=span.makeCurrent()) {
            new TelemetryTraceFilter().doFilter(request,new MockHttpServletResponse(),(r,s)->{
                var wrapped=(HttpServletRequest)r;
                assertEquals("00-"+TRACE+"-"+SPAN+"-01",wrapped.getHeader("Traceparent"));
                assertEquals(wrapped.getHeader("traceparent"),Collections.list(wrapped.getHeaders("traceparent")).getFirst());
                assertTrue(Collections.list(wrapped.getHeaderNames()).contains("traceparent"));
                assertEquals("opaque",wrapped.getHeader("Cookie"));
            });
        }
    }
    @Test void validExternalParentIsRetained() throws Exception {
        var request=new MockHttpServletRequest();var parent="00-"+"a".repeat(32)+"-"+"b".repeat(16)+"-01";request.addHeader("traceparent",parent);
        new TelemetryTraceFilter().doFilter(request,new MockHttpServletResponse(),(r,s)->assertSame(request,r));
    }
    @Test void withoutAgentExistingFallbackIsUntouched() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("traceparent","invalid");
        new TelemetryTraceFilter().doFilter(request,new MockHttpServletResponse(),(r,s)->assertSame(request,r));
    }
    @Test void invalidParentUsesActiveTraceWithoutTrustingInvalidInput() throws Exception {
        var request=new MockHttpServletRequest();request.addHeader("traceparent","00-"+"0".repeat(32)+"-"+SPAN+"-01");
        var span=Span.wrap(SpanContext.create(TRACE,SPAN,TraceFlags.getDefault(),TraceState.getDefault()));
        try(var scope=span.makeCurrent()) {new TelemetryTraceFilter().doFilter(request,new MockHttpServletResponse(),(r,s)->{
            assertEquals("00-"+TRACE+"-"+SPAN+"-00",((HttpServletRequest)r).getHeader("traceparent"));
        });}
    }
}

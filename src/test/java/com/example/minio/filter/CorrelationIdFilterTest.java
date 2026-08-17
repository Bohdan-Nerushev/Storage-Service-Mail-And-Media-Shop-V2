package com.example.minio.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        // Ensure MDC is clean between tests regardless of filter behaviour
        MDC.clear();
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Propagates an existing X-Correlation-ID from the incoming request into MDC")
    void propagatesExistingCorrelationId() throws IOException, ServletException {
        final String correlationId = "my-trace-id-123";
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CORRELATION_HEADER, correlationId);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        // Capture the MDC value from inside the filter chain
        final String[] capturedId = new String[1];
        final FilterChain chain = (req, res) -> capturedId[0] = MDC.get(MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(capturedId[0]).isEqualTo(correlationId);
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Generates a new UUID correlation ID when the X-Correlation-ID header is absent")
    void generatesNewCorrelationIdWhenHeaderAbsent() throws IOException, ServletException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final String[] capturedId = new String[1];
        final FilterChain chain = (req, res) -> capturedId[0] = MDC.get(MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(capturedId[0])
                .isNotNull()
                .isNotBlank()
                // Must be a valid UUID format (generated internally)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Clears MDC after filter chain execution to prevent leakage between requests")
    void clearsMdcAfterFilterChain() throws IOException, ServletException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CORRELATION_HEADER, "some-id");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}

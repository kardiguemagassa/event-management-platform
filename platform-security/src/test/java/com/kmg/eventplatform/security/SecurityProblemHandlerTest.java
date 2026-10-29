package com.kmg.eventplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class SecurityProblemHandlerTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final SecurityProblemHandler handler = new SecurityProblemHandler(resolver);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/registrations");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void failureWrittenByMvcIsLeftAsIs() throws Exception {
        when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(new ModelAndView());

        handler.commence(request, response, new InsufficientAuthenticationException("anonymous"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void unhandledAuthenticationFailureStillSendsA401() throws Exception {
        handler.commence(request, response, new InsufficientAuthenticationException("anonymous"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unhandledAccessDeniedStillSendsA403() throws Exception {
        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}

package com.careerai.backend.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;

import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAuthenticationFilterTest {
    private AdminProperties properties;
    private AdminAuthenticationService authentication;
    private AdminAuthenticationFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        properties = new AdminProperties();
        properties.setEnabled(true);
        authentication = mock(AdminAuthenticationService.class);
        chain = mock(FilterChain.class);
        filter = new AdminAuthenticationFilter(authentication, properties);
    }

    @ParameterizedTest
    @MethodSource("adminMutationRoutes")
    void allMutationRoutesRequireAuthentication(String route) throws Exception {
        String[] parts = route.split(" ", 2);
        MockHttpServletRequest request = request(parts[0], parts[1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus(), route);
        verifyNoInteractions(chain, authentication);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    static Stream<String> adminMutationRoutes() {
        return Stream.of("POST /api/admin/posts/1/archive", "POST /api/admin/posts/1/restore",
                "POST /api/admin/posts/1/freshness", "POST /api/admin/posts/1/extract",
                "PUT /api/admin/posts/1/metadata", "PUT /api/admin/posts/1/date-confirmation",
                "POST /api/admin/posts/1/date-confirmation/revoke", "POST /api/admin/posts/1/reindex",
                "POST /api/admin/posts/1/discover", "POST /api/admin/faqs", "PUT /api/admin/faqs/1",
                "POST /api/admin/relations", "POST /api/admin/relations/1/confirm",
                "POST /api/admin/relations/1/remove", "POST /api/admin/relations/1/retry",
                "POST /api/admin/candidates/1/approve", "POST /api/admin/candidates/1/reject",
                "POST /api/admin/candidates/1/retry", "POST /api/admin/jobs/embeddings",
                "POST /api/admin/jobs/candidates", "DELETE /api/admin/anything", "PATCH /api/admin/anything");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/admin/", "/api/admin/me", "/api/admin/overview",
            "/api/admin/posts", "/api/admin/posts/1", "/api/admin/faqs", "/api/admin/relations",
            "/api/admin/candidates", "/api/admin/candidates/1/audit", "/api/admin/audit", "/api/admin/jobs"})
    void allReadRoutesArePrivate(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", path), response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void attachesOnlyServerValidatedIdentityAndDoesNotTrustFrontendIds() throws Exception {
        AdminIdentity validated = new AdminIdentity(424242, "Administrator");
        when(authentication.authenticate("signed-init-data")).thenReturn(validated);
        MockHttpServletRequest request = request("POST", "/api/admin/faqs");
        request.addHeader("Authorization", "tma signed-init-data");
        request.addHeader("X-Telegram-User-Id", "999999");
        request.addParameter("user_id", "999999");
        request.setAttribute(AdminAuthenticationFilter.IDENTITY, new AdminIdentity(999999, "Untrusted"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(validated, request.getAttribute(AdminAuthenticationFilter.IDENTITY));
        verify(chain).doFilter(argThat(wrapped -> validated.equals(wrapped.getAttribute(AdminAuthenticationFilter.IDENTITY))), same(response));
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void cookiesBodyQueryAndExistingRequestIdentityDoNotSubstituteAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/admin/relations");
        request.addParameter("initData", "signed-init-data");
        request.addHeader("X-Telegram-User-Id", "424242");
        request.setCookies(new Cookie("initData", "signed-init-data"));
        request.setContent("{\"user_id\":424242,\"initData\":\"signed-init-data\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setAttribute(AdminAuthenticationFilter.IDENTITY, new AdminIdentity(424242, "Forged"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain, authentication);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer signed-init-data", "Basic signed-init-data", "signed-init-data", "TMA signed-init-data"})
    void rejectsUnexpectedAuthorizationSchemes(String header) throws Exception {
        MockHttpServletRequest request = request("GET", "/api/admin/me");
        request.addHeader("Authorization", header);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain, authentication);
    }

    @Test
    void failedAuthenticationNeverLeaksExceptionOrSignedPayload() throws Exception {
        when(authentication.authenticate("secret-signed-payload"))
                .thenThrow(new SecurityException("internal-bot-token-and-stacktrace"));
        MockHttpServletRequest request = request("POST", "/api/admin/jobs/embeddings");
        request.addHeader("Authorization", "tma secret-signed-payload");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains("internal-bot-token"));
        assertFalse(response.getContentAsString().contains("secret-signed-payload"));
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        verifyNoInteractions(chain);
    }

    @Test
    void disabledAdminApiIsUnavailableEvenWithAuthorization() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = request("POST", "/api/admin/faqs");
        request.addHeader("Authorization", "tma signed-init-data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(404, response.getStatus());
        verifyNoInteractions(chain, authentication);
    }

    @Test
    void rejectsKnownOversizedRequestBeforeAuthenticationAndMutation() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/admin/faqs");
        request.setContent(new byte[131073]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(413, response.getStatus());
        verifyNoInteractions(chain, authentication);
    }

    @Test
    void rejectsChunkedOversizedBodyWithoutInvokingController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/faqs") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath("/api/admin/faqs");
        request.addHeader("Authorization", "tma signed-init-data");
        request.setContent(new byte[131073]);
        when(authentication.authenticate("signed-init-data")).thenReturn(new AdminIdentity(424242, "Admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(413, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void forwardsAcceptedBodyWithoutLosingBytes() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/admin/faqs");
        request.addHeader("Authorization", "tma signed-init-data");
        byte[] expected = "{\"question\":\"Практика?\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request.setContent(expected);
        when(authentication.authenticate("signed-init-data")).thenReturn(new AdminIdentity(424242, "Admin"));
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) ->
                assertArrayEquals(expected, wrapped.getInputStream().readAllBytes()));
    }

    @Test
    void staticAdminShellIsPublicButHasProtectiveHeaders() throws Exception {
        MockHttpServletRequest request = request("GET", "/admin/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(authentication);
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("object-src 'none'"));
    }

    @Test
    void unrelatedPublicPathDoesNotRequireAdminAccess() throws Exception {
        MockHttpServletRequest request = request("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(authentication);
    }

    @Test
    void everyControllerMutationReceivesServerValidatedActorForAudit() {
        var mutations = java.util.Arrays.stream(AdminController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class))
                .toList();
        assertFalse(mutations.isEmpty());
        for (var method : mutations) {
            boolean validatedActor = java.util.Arrays.stream(method.getParameters()).anyMatch(parameter -> {
                RequestAttribute attribute = parameter.getAnnotation(RequestAttribute.class);
                return parameter.getType() == AdminIdentity.class && attribute != null
                        && attribute.value().equals(AdminAuthenticationFilter.IDENTITY) && attribute.required();
            });
            assertTrue(validatedActor, method.getName() + " must use the identity verified by the server filter");
        }
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}

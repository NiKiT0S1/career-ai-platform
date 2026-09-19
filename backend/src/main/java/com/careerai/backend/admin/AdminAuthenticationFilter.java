package com.careerai.backend.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(-100)
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    public static final String IDENTITY = "com.careerai.backend.admin.AdminIdentity";
    private static final int MAX_BODY_BYTES = 131072;
    private final AdminAuthenticationService authentication;
    private final AdminProperties properties;
    public AdminAuthenticationFilter(AdminAuthenticationService authentication, AdminProperties properties) {
        this.authentication = authentication; this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.startsWith("/admin") || path.startsWith("/api/admin")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' https://telegram.org; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'self' https://web.telegram.org https://*.telegram.org");
        }
        if (!(path.equals("/api/admin") || path.startsWith("/api/admin/"))) { chain.doFilter(request, response); return; }
        if (!properties.isEnabled()) { error(response, 404, "Панель администратора выключена"); return; }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) { error(response, 413, "Запрос слишком большой"); return; }
        String header = request.getHeader("Authorization");
        try {
            if (header == null || !header.startsWith("tma ")) throw new SecurityException();
            request.setAttribute(IDENTITY, authentication.authenticate(header.substring(4)));
        } catch (SecurityException e) { error(response, 401, "Откройте панель заново из Telegram с разрешённого аккаунта"); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) { error(response, 413, "Запрос слишком большой"); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

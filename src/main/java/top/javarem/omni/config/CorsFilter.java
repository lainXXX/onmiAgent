package top.javarem.omni.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(CorsFilter.class);

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;

        String origin = request.getHeader("Origin");
        String method = request.getMethod();

        log.info("[CORS Filter] Method: {}, Origin: {}, URI: {}", method, origin, request.getRequestURI());

        // Handle null origin (e.g., from file:// or special browsers)
        if (origin == null || origin.isEmpty()) {
            origin = request.getHeader("Referer");
        }
        if (origin == null || origin.isEmpty()) {
            origin = "*";
        }

        // For credentials, origin must be specific, not "*"
        if ("*".equals(origin)) {
            origin = "http://localhost:9501";
        }

        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "*");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Max-Age", "3600");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            response.setStatus(HttpServletResponse.SC_OK);
            log.info("[CORS Filter] Handled OPTIONS preflight");
        } else {
            chain.doFilter(req, res);
        }
    }
}
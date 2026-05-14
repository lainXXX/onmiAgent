package top.javarem.omni.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import top.javarem.omni.dto.AuthRequest;
import top.javarem.omni.dto.AuthResponse;
import top.javarem.omni.model.User;
import top.javarem.omni.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request, HttpServletResponse response) {
        try {
            User user = authService.register(
                request.getUsername(),
                request.getPassword(),
                request.getConfirmPassword(),
                response
            );
            return ResponseEntity.ok(new AuthResponse("注册成功", user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletResponse response) {
        try {
            User user = authService.login(request.getUsername(), request.getPassword(), response);
            return ResponseEntity.ok(new AuthResponse("登录成功", user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        authService.logout(response);
        return ResponseEntity.ok(Map.of("message", "登出成功"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        String username = auth.getName();
        return authService.getCurrentUser(username)
            .map(user -> ResponseEntity.ok(Map.of("username", user.getUsername())))
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "用户不存在")));
    }
}
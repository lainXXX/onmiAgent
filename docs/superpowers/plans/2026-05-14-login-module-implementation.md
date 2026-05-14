# 登录模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现用户注册、登录、登出功能，JWT + httpOnly Cookie 认证，前端登录状态管理

**Architecture:**
- 后端: Spring Security 6 + Spring Data JPA + jjwt
- 前端: React Router + Context API + Tailwind CSS
- 数据库: MySQL users 表
- 认证: JWT 存储在 httpOnly Cookie，BCrypt 密码加密

**Tech Stack:** Spring Boot 3.5.10, Spring Security 6, jjwt 0.12.x, React 18, TypeScript, Tailwind CSS

---

## 后端实现

### Task 1: 后端依赖和配置

**Files:**
- Modify: `pom.xml` - 添加 jjwt, spring-security, mysql-connector 依赖
- Modify: `src/main/resources/application-dev.yml` - 添加 JWT 和 CORS 配置

- [ ] **Step 1: 修改 pom.xml 添加依赖**

在 `<dependencies>` 中添加:

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 验证依赖**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw dependency:tree -Dincludes=io.jsonwebtoken:jjwt-api`
Expected: 显示 jjwt 依赖树

---

### Task 2: 数据库表和实体

**Files:**
- Create: `src/main/java/top/javarem/omni/model/User.java` - 用户实体
- Create: `src/main/java/top/javarem/omni/repository/UserRepository.java` - 用户仓储

- [ ] **Step 1: 创建 User.java**

```java
package top.javarem.omni.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: 创建 UserRepository.java**

```java
package top.javarem.omni.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

- [ ] **Step 3: 创建 SQL 迁移脚本**

```sql
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密后的密码',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

- [ ] **Step 4: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

### Task 3: JWT 工具类

**Files:**
- Create: `src/main/java/top/javarem/omni/utils/JwtUtils.java`

- [ ] **Step 1: 创建 JwtUtils.java**

```java
package top.javarem.omni.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtils {

    @Value("${app.jwt.secret}")
    private static String jwtSecret;

    @Value("${app.jwt.expiration}")
    private static long jwtExpiration;

    public static String generateToken(String username) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(key)
                .compact();
    }

    public static String parseUsername(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

### Task 4: JWT 认证过滤器

**Files:**
- Create: `src/main/java/top/javarem/omni/security/JwtAuthenticationFilter.java`
- Create: `src/main/java/top/javarem/omni/security/JwtAuthenticationEntryPoint.java`

- [ ] **Step 1: 创建 JwtAuthenticationFilter.java**

```java
package top.javarem.omni.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.javarem.omni.utils.JwtUtils;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String username = extractUsernameFromCookie(request);
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }
        filterChain.doFilter(request, response);
    }

    private String extractUsernameFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("jwt".equals(cookie.getName())) {
                try {
                    return JwtUtils.parseUsername(cookie.getValue());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: 创建 JwtAuthenticationEntryPoint.java**

```java
package top.javarem.omni.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

### Task 5: Security 配置

**Files:**
- Create: `src/main/java/top/javarem/omni/config/SecurityConfig.java`

- [ ] **Step 1: 创建 SecurityConfig.java**

```java
package top.javarem.omni.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import top.javarem.omni.security.JwtAuthenticationEntryPoint;
import top.javarem.omni.security.JwtAuthenticationFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/api/auth/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins));
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

### Task 6: Auth 服务和控制器

**Files:**
- Create: `src/main/java/top/javarem/omni/service/AuthService.java`
- Create: `src/main/java/top/javarem/omni/controller/AuthController.java`
- Create: `src/main/java/top/javarem/omni/dto/AuthRequest.java`
- Create: `src/main/java/top/javarem/omni/dto/AuthResponse.java`

- [ ] **Step 1: 创建 AuthRequest.java**

```java
package top.javarem.omni.dto;

public class AuthRequest {
    private String username;
    private String password;
    private String confirmPassword;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
```

- [ ] **Step 2: 创建 AuthResponse.java**

```java
package top.javarem.omni.dto;

public class AuthResponse {
    private String message;
    private String username;

    public AuthResponse(String message, String username) {
        this.message = message;
        this.username = username;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
```

- [ ] **Step 3: 创建 AuthService.java**

```java
package top.javarem.omni.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import top.javarem.omni.model.User;
import top.javarem.omni.repository.UserRepository;
import top.javarem.omni.utils.JwtUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(String username, String password, String confirmPassword, HttpServletResponse response) {
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("两次密码输入不一致");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        User saved = userRepository.save(user);
        return saved;
    }

    public User login(String username, String password, HttpServletResponse response) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = JwtUtils.generateToken(username);
        Cookie cookie = new Cookie("jwt", token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(604800); // 7 days
        response.addCookie(cookie);
        return user;
    }

    public void logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("jwt", "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    public Optional<User> getCurrentUser(String username) {
        return userRepository.findByUsername(username);
    }
}
```

- [ ] **Step 4: 创建 AuthController.java**

```java
package top.javarem.omni.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<?> me(@RequestAttribute("username") String username) {
        return authService.getCurrentUser(username)
            .map(user -> ResponseEntity.ok(Map.of("username", user.getUsername())))
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录")));
    }
}
```

- [ ] **Step 5: 修复 Controller 的 me 接口**

由于 JwtAuthenticationFilter 已经设置了 SecurityContext，直接用 SecurityContext 获取用户:

```java
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
```

- [ ] **Step 6: 添加 SecurityContextHolder 导入**

在 AuthController.java 顶部添加:
```java
import org.springframework.security.core.context.SecurityContextHolder;
```

- [ ] **Step 7: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

### Task 7: 配置更新

**Files:**
- Modify: `src/main/resources/application-dev.yml` - 添加 JWT 和 CORS 配置

- [ ] **Step 1: 添加配置项**

在 application-dev.yml 末尾添加:

```yaml
app:
  jwt:
    secret: ${JWT_SECRET:your-256-bit-secret-key-here-change-in-production-must-be-at-least-32-chars}
    expiration: 604800000  # 7 days in ms
  cors:
    allowed-origins: http://localhost:5173
```

- [ ] **Step 2: 验证配置加载**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw compile -q`
Expected: 编译成功

---

## 前端实现

### Task 8: 前端 API 层

**Files:**
- Create: `frontend/src/api/auth.ts`

- [ ] **Step 1: 创建 auth.ts**

```typescript
export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  confirmPassword: string;
}

export interface AuthResponse {
  message: string;
  username?: string;
  error?: string;
}

export const login = async (username: string, password: string): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json();
  return data;
};

export const register = async (username: string, password: string, confirmPassword: string): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password, confirmPassword }),
  });
  const data = await res.json();
  return data;
};

export const logout = async (): Promise<AuthResponse> => {
  const res = await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
  });
  const data = await res.json();
  return data;
};

export const getCurrentUser = async (): Promise<{ username: string } | null> => {
  try {
    const res = await fetch('/api/auth/me', {
      credentials: 'include',
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data;
  } catch {
    return null;
  }
};
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npx tsc --noEmit`
Expected: 无错误

---

### Task 9: Auth Context

**Files:**
- Create: `frontend/src/context/AuthContext.tsx`

- [ ] **Step 1: 创建 AuthContext.tsx**

```typescript
import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { login as apiLogin, register as apiRegister, logout as apiLogout, getCurrentUser } from '../api/auth';

interface User {
  username: string;
}

interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<{ error?: string }>;
  register: (username: string, password: string, confirmPassword: string) => Promise<{ error?: string }>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    checkAuth();
  }, []);

  const checkAuth = async () => {
    try {
      const data = await getCurrentUser();
      if (data?.username) {
        setUser({ username: data.username });
      }
    } catch (e) {
      console.error('Check auth error:', e);
    } finally {
      setIsLoading(false);
    }
  };

  const login = async (username: string, password: string) => {
    const data = await apiLogin(username, password);
    if (data.error) return { error: data.error };
    setUser({ username: data.username! });
    return {};
  };

  const register = async (username: string, password: string, confirmPassword: string) => {
    const data = await apiRegister(username, password, confirmPassword);
    if (data.error) return { error: data.error };
    return {};
  };

  const logout = async () => {
    await apiLogout();
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, isLoading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
```

- [ ] **Step 2: 验证 TypeScript 编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npx tsc --noEmit`
Expected: 无错误

---

### Task 10: 登录和注册页面

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`
- Create: `frontend/src/pages/RegisterPage.tsx`

- [ ] **Step 1: 创建 LoginPage.tsx**

```typescript
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setIsLoading(true);
    const result = await login(username, password);
    setIsLoading(false);
    if (result.error) {
      setError(result.error);
    } else {
      navigate('/');
    }
  };

  return (
    <div className="min-h-screen bg-zinc-950 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-6">
          <h1 className="text-xl font-semibold text-zinc-100 mb-6 text-center">登录</h1>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm text-zinc-400 mb-1">用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-zinc-100 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="请输入用户名"
                required
              />
            </div>
            <div>
              <label className="block text-sm text-zinc-400 mb-1">密码</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-zinc-100 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="请输入密码"
                required
              />
            </div>
            {error && <p className="text-sm text-red-400">{error}</p>}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-600/50 text-white rounded-lg py-2 font-medium transition-colors"
            >
              {isLoading ? '登录中...' : '登录'}
            </button>
          </form>
          <p className="text-sm text-zinc-500 text-center mt-4">
            还没有账户？<Link to="/register" className="text-blue-400 hover:text-blue-300">立即注册</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 创建 RegisterPage.tsx**

```typescript
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export function RegisterPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const { register } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (password !== confirmPassword) {
      setError('两次密码输入不一致');
      return;
    }
    if (password.length < 6) {
      setError('密码至少6个字符');
      return;
    }
    setIsLoading(true);
    const result = await register(username, password, confirmPassword);
    setIsLoading(false);
    if (result.error) {
      setError(result.error);
    } else {
      navigate('/login');
    }
  };

  return (
    <div className="min-h-screen bg-zinc-950 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="bg-zinc-900 border border-zinc-800 rounded-xl p-6">
          <h1 className="text-xl font-semibold text-zinc-100 mb-6 text-center">注册</h1>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm text-zinc-400 mb-1">用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-zinc-100 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="请输入用户名 (3-20字符)"
                minLength={3}
                maxLength={20}
                required
              />
            </div>
            <div>
              <label className="block text-sm text-zinc-400 mb-1">密码</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-zinc-100 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="请输入密码 (至少6字符)"
                minLength={6}
                required
              />
            </div>
            <div>
              <label className="block text-sm text-zinc-400 mb-1">确认密码</label>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="w-full bg-zinc-800 border border-zinc-700 rounded-lg px-3 py-2 text-zinc-100 placeholder-zinc-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="请再次输入密码"
                required
              />
            </div>
            {error && <p className="text-sm text-red-400">{error}</p>}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-600/50 text-white rounded-lg py-2 font-medium transition-colors"
            >
              {isLoading ? '注册中...' : '注册'}
            </button>
          </form>
          <p className="text-sm text-zinc-500 text-center mt-4">
            已有账户？<Link to="/login" className="text-blue-400 hover:text-blue-300">立即登录</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: 验证编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npx tsc --noEmit`
Expected: 无错误

---

### Task 11: PrivateRoute 组件和路由配置

**Files:**
- Create: `frontend/src/components/PrivateRoute.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: 创建 PrivateRoute.tsx**

```typescript
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

interface PrivateRouteProps {
  children: React.ReactNode;
}

export function PrivateRoute({ children }: PrivateRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-zinc-950 flex items-center justify-center">
        <div className="text-zinc-400">加载中...</div>
      </div>
    );
  }

  return isAuthenticated ? <>{children}</> : <Navigate to="/login" />;
}
```

- [ ] **Step 2: 创建 AuthRoute.tsx (已登录用户访问登录/注册页)**

```typescript
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

interface AuthRouteProps {
  children: React.ReactNode;
}

export function AuthRoute({ children }: AuthRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-zinc-950 flex items-center justify-center">
        <div className="text-zinc-400">加载中...</div>
      </div>
    );
  }

  return isAuthenticated ? <Navigate to="/" /> : <>{children}</>;
}
```

- [ ] **Step 3: 创建 pages 目录并移动文件**

确保 pages 目录存在:
```bash
mkdir -p frontend/src/pages
```

- [ ] **Step 4: 修改 App.tsx 添加路由**

在 App.tsx 顶部添加:
```typescript
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { PrivateRoute } from './components/PrivateRoute';
import { AuthRoute } from './components/AuthRoute';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
```

修改 App 组件外层:
```typescript
export function App() {
  // ... existing state and logic
  return (
    <BrowserRouter>
      <AuthProvider>
        <div className="flex h-full bg-zinc-950">
          {/* ... existing UI */}
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}
```

添加路由:
```typescript
// 在 return (
//   <BrowserRouter>
//     <AuthProvider>
// 内添加 Routes
<Routes>
  <Route path="/login" element={<AuthRoute><LoginPage /></AuthRoute>} />
  <Route path="/register" element={<AuthRoute><RegisterPage /></AuthRoute>} />
  <Route path="/" element={<PrivateRoute><div className="flex h-full bg-zinc-950">{/* existing main content */}</div></PrivateRoute>} />
</Routes>
```

- [ ] **Step 5: 验证 TypeScript 编译**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npx tsc --noEmit`
Expected: 无错误

---

### Task 12: 前端依赖

**Files:**
- Modify: `frontend/package.json` - 添加 react-router-dom 依赖

- [ ] **Step 1: 添加依赖**

```bash
cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npm install react-router-dom
```

- [ ] **Step 2: 验证依赖安装**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module/frontend && npm list react-router-dom`
Expected: 显示 react-router-dom 版本

---

## 集成测试

### Task 13: 验证前后端联调

- [ ] **Step 1: 后端启动测试**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
Expected: 应用启动成功

- [ ] **Step 2: 测试注册接口**

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123","confirmPassword":"password123"}'
```
Expected: `{"message":"注册成功","username":"testuser"}`

- [ ] **Step 3: 测试登录接口**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'
```
Expected: 响应头包含 `Set-Cookie: jwt=...`

- [ ] **Step 4: 测试获取当前用户**

```bash
curl -X GET http://localhost:8080/api/auth/me \
  --cookie "jwt=<token from login>"
```
Expected: `{"username":"testuser"}`

- [ ] **Step 5: 测试登出接口**

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  --cookie "jwt=<token>"
```
Expected: `Set-Cookie: jwt=; Max-Age=0`

---

## 提交代码

### Task 14: Git 提交

- [ ] **Step 1: 查看变更**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && git status`

- [ ] **Step 2: 提交所有变更**

```bash
git add .
git commit -m "feat(auth): 实现用户注册登录模块

- 用户注册/登录/登出功能
- JWT + httpOnly Cookie 认证
- BCrypt 密码加密
- 前端 React Router 路由和状态管理"
```

- [ ] **Step 3: 验证提交**

Run: `cd D:/Develop/ai/omniAgent/.worktrees/login-module && git log -1 --oneline`
Expected: 显示刚提交的 commit
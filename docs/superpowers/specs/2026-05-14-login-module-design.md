# 登录模块设计文档

## 1. 概述

### 1.1 功能范围

| 功能 | 状态 |
|------|------|
| 用户注册（用户名 + 密码 + 确认密码） | 新增 |
| 用户登录（JWT + httpOnly Cookie） | 新增 |
| 用户登出 | 新增 |
| 获取当前用户信息 | 新增 |
| 前端登录状态管理 | 新增 |

### 1.2 技术选型

- **前端**: React 18 + Vite + TypeScript + Tailwind CSS
- **后端**: Spring Boot 3.5.10 + Spring Security 6 + Spring Data JPA
- **认证**: JWT (jjwt library) + httpOnly Cookie
- **密码加密**: BCrypt (Spring Security 内置)
- **数据库**: MySQL (已有 spring_ai_chat_memory 库)

---

## 2. 后端设计

### 2.1 数据库表

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密后的密码',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

### 2.2 实体类

```
src/main/java/top/javarem/omni/model/User.java
```

| 字段 | 类型 | 约束 |
|------|------|------|
| id | Long | @Id, @GeneratedValue |
| username | String | @Column(unique=true), 50字符 |
| password | String | @Column, 255字符 |
| createdAt | LocalDateTime | @CreatedDate |

### 2.3 仓储层

```
src/main/java/top/javarem/omni/repository/UserRepository.java
```

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

### 2.4 服务层

```
src/main/java/top/javarem/omni/service/AuthService.java
```

| 方法 | 说明 |
|------|------|
| `register(username, password, confirmPassword)` | 注册用户，校验密码匹配、用户名唯一 |
| `login(username, password)` | 验证密码，生成 JWT，设置 Cookie |
| `logout()` | 清除 JWT Cookie |
| `getCurrentUser(username)` | 获取当前用户信息 |

### 2.5 控制层

```
src/main/java/top/javarem/omni/controller/AuthController.java
```

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/auth/register` | POST | 注册 {username, password, confirmPassword} |
| `/api/auth/login` | POST | 登录 {username, password} → Set-Cookie |
| `/api/auth/logout` | POST | 登出 → Clear-Cookie |
| `/api/auth/me` | GET | 获取当前用户信息 |

### 2.6 安全配置

```
src/main/java/top/javarem/omni/config/SecurityConfig.java
```

- **路径白名单**: `/api/auth/login`, `/api/auth/register`, `/api/auth/**` (允许访问)
- **其他路径**: 需要认证
- **CORS**: 允许前端 origin (配置化)
- **Filter**: `JwtAuthenticationFilter` 验证 Cookie 中的 JWT

### 2.7 JWT 工具类

```
src/main/java/top/javarem/omni/utils/JwtUtils.java
```

| 方法 | 说明 |
|------|------|
| `generateToken(username)` | 生成 JWT |
| `parseUsername(token)` | 从 JWT 解析用户名 |

### 2.8 JWT 认证过滤器

```
src/main/java/top/javarem/omni/security/JwtAuthenticationFilter.java
```

- 从 httpOnly Cookie 读取 JWT
- 验证并设置 SecurityContext
- 无 token 时跳过（交给 Spring Security 处理）

---

## 3. 前端设计

### 3.1 目录结构

```
frontend/src/
├── api/
│   └── auth.ts              # Auth API 调用
├── context/
│   └── AuthContext.tsx      # 登录状态管理
├── pages/
│   ├── LoginPage.tsx        # 登录页
│   └── RegisterPage.tsx    # 注册页
├── components/
│   └── PrivateRoute.tsx     # 受保护路由
├── App.tsx                  # 路由配置
└── main.tsx                 # 入口
```

### 3.2 API 层

```
frontend/src/api/auth.ts
```

```typescript
export const login = (username: string, password: string) => fetch('/api/auth/login', {...})
export const register = (username: string, password: string, confirmPassword: string) => fetch('/api/auth/register', {...})
export const logout = () => fetch('/api/auth/logout', { method: 'POST' })
export const getCurrentUser = () => fetch('/api/auth/me')
```

### 3.3 状态管理

```
frontend/src/context/AuthContext.tsx
```

| 状态 | 类型 | 说明 |
|------|------|------|
| user | `{username: string}` \| null | 当前用户 |
| isAuthenticated | boolean | 是否已登录 |
| login(credentials) | function | 登录 |
| register(credentials) | function | 注册 |
| logout() | function | 登出 |

### 3.4 受保护路由

```
frontend/src/components/PrivateRoute.tsx
```

- 未登录用户访问时重定向到 `/login`
- 已登录用户访问 `/login` 或 `/register` 时重定向到首页

### 3.5 页面设计

#### 登录页 (`/login`)

| 字段 | 类型 | 校验 |
|------|------|------|
| 用户名 | text | 必填 |
| 密码 | password | 必填 |
| 登录按钮 | button | 提交 |
| 注册链接 | link | → `/register` |

#### 注册页 (`/register`)

| 字段 | 类型 | 校验 |
|------|------|------|
| 用户名 | text | 必填，3-20字符 |
| 密码 | password | 必填，最少6字符 |
| 确认密码 | password | 必填，与密码相同 |
| 注册按钮 | button | 提交 |
| 登录链接 | link | → `/login` |

---

## 4. 安全设计

### 4.1 密码安全

- 存储: BCrypt strength=10
- 传输: HTTPS (生产环境)

### 4.2 JWT 配置

| 配置项 | 值 |
|------|------|
| 算法 | HS256 |
| 过期时间 | 7 天 |
| 存储方式 | httpOnly Cookie |
| SameSite | Strict |

### 4.3 CORS 配置

从 `application-dev.yml` 读取前端 origin，支持配置化。

---

## 5. 配置项

### 5.1 后端配置 (`application-dev.yml`)

```yaml
app:
  jwt:
    secret: ${JWT_SECRET:your-256-bit-secret-key-here-change-in-production}
    expiration: 604800000  # 7 days in ms
  cors:
    allowed-origins: http://localhost:5173
```

---

## 6. 错误处理

### 6.1 后端异常

| 错误码 | 说明 |
|------|------|
| 400 | 注册失败（用户名已存在、密码不匹配） |
| 401 | 登录失败（用户名或密码错误） |

### 6.2 前端错误提示

- 注册: "用户名已存在"、"两次密码输入不一致"
- 登录: "用户名或密码错误"

---

## 7. 验收标准

- [ ] 用户可以注册新账户
- [ ] 用户可以登录并获得 JWT Cookie
- [ ] 未登录用户访问聊天页被重定向到登录页
- [ ] 已登录用户访问登录/注册页被重定向到首页
- [ ] 用户可以登出
- [ ] 密码以 BCrypt 加密存储
- [ ] JWT 存储在 httpOnly Cookie 中
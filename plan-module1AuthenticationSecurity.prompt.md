# 模块一：基础架构与安全认证模块 - 深度教学文档

---

## 🎯 模块概述

本模块是整个PaiSmart系统的**安全基石**，负责用户身份认证、权限管理、会话控制等核心功能。学习本模块后，你将掌握：

1. **JWT（JSON Web Token）认证机制**
2. **Spring Security安全框架**
3. **Redis分布式会话管理**
4. **多租户组织权限控制**
5. **Token自动刷新与黑名单机制**

---

## 📖 第一章：JWT认证核心原理

### 1.1 什么是JWT？

JWT是一种**无状态**的身份认证方案，由三部分组成：

```
Header.Payload.Signature
```

- **Header**：算法类型（如HS256）
- **Payload**：用户信息（Claims）
- **Signature**：使用密钥签名，防止篡改

### 1.2 JWT vs 传统Session的区别

| 特性 | 传统Session | JWT |
|------|------------|-----|
| 存储位置 | 服务器内存/数据库 | 客户端（HTTP Header） |
| 扩展性 | 难以水平扩展 | 天然支持分布式 |
| 性能 | 每次请求需查询Session | 自包含信息，无需查询 |
| 安全性 | 依赖SessionID | 依赖签名验证 |

### 1.3 PaiSmart的JWT实现架构

本项目采用**JWT + Redis混合方案**，结合了两者优势：

```
┌─────────────┐      ┌──────────────┐      ┌─────────────┐
│   前端      │─────→│ Spring Boot  │─────→│   Redis     │
│  (Token)    │  ①请求│  (过滤器链)   │  ②验证│  (缓存)     │
└─────────────┘      └──────────────┘      └─────────────┘
                           │ ③认证成功
                           ↓
                     ┌──────────────┐
                     │ SecurityContext│
                     │  (用户信息)    │
                     └──────────────┘
```

---

## 📖 第二章：JwtUtils工具类详解

### 2.1 核心常量配置

```java
// Token有效期：1小时
private static final long EXPIRATION_TIME = 3600000;

// RefreshToken有效期：7天
private static final long REFRESH_TOKEN_EXPIRATION_TIME = 604800000;

// 刷新阈值：剩余5分钟时触发自动刷新
private static final long REFRESH_THRESHOLD = 300000;

// 宽限期：Token过期后10分钟内仍可刷新
private static final long REFRESH_WINDOW = 600000;
```

**设计思想**：
- **1小时有效期**：平衡安全性与用户体验
- **7天刷新期**：减少频繁登录
- **5分钟预刷新**：用户无感知的Token续期
- **10分钟宽限期**：容忍网络延迟，避免突然掉线

### 2.2 场景一：用户登录 - Token生成流程

**业务场景**：用户输入用户名密码，点击登录按钮

```java
public String generateToken(String username) {
    SecretKey key = getSigningKey(); // ① 获取密钥
    
    // ② 从数据库查询用户信息
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));
    
    // ③ 生成唯一tokenId（用于Redis缓存和黑名单）
    String tokenId = generateTokenId(); // UUID去掉横杠
    long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;
    
    // ④ 构建JWT的Payload（Claims）
    Map<String, Object> claims = new HashMap<>();
    claims.put("tokenId", tokenId);           // Token唯一标识
    claims.put("role", user.getRole().name()); // 角色：USER/ADMIN
    claims.put("userId", user.getId().toString()); // 用户ID
    claims.put("orgTags", user.getOrgTags());  // 组织标签（多租户）
    claims.put("primaryOrg", user.getPrimaryOrg()); // 主组织
    
    // ⑤ 生成JWT字符串
    String token = Jwts.builder()
            .setClaims(claims)              // 设置Payload
            .setSubject(username)           // 设置主题（用户名）
            .setExpiration(new Date(expireTime)) // 设置过期时间
            .signWith(key, SignatureAlgorithm.HS256) // 使用HS256算法签名
            .compact();
    
    // ⑥ 缓存Token到Redis（关键！）
    tokenCacheService.cacheToken(tokenId, user.getId().toString(), 
                                  username, expireTime);
    
    logger.info("Token generated for user: {}, tokenId: {}", username, tokenId);
    return token;
}
```

**实际数据流**：

假设用户 `zhangsan` 登录：

```json
// 生成的JWT内容（解码后的Payload）
{
  "tokenId": "a1b2c3d4e5f6789012345678",
  "role": "USER",
  "userId": "123",
  "orgTags": "tech_dept,product_dept",
  "primaryOrg": "tech_dept",
  "sub": "zhangsan",
  "exp": 1735833600000
}
```

```
// Redis中存储的数据
Key:   jwt:valid:a1b2c3d4e5f6789012345678
Value: {
  "userId": "123",
  "username": "zhangsan",
  "expireTime": 1735833600000
}
TTL:   3900秒（1小时+5分钟缓冲）
```

**为什么要Redis缓存？**
1. **主动失效**：用户登出时，加入黑名单，立即失效
2. **性能优化**：快速判断Token是否有效，无需每次解析JWT
3. **强制下线**：管理员可强制用户下线

### 2.3 场景二：请求验证 - Token校验流程

**业务场景**：用户上传文件，请求头携带Token

```java
public boolean validateToken(String token) {
    try {
        // ① 快速失败：提取tokenId
        String tokenId = extractTokenIdFromToken(token);
        if (tokenId == null) {
            logger.warn("Token does not contain tokenId");
            return false;
        }
        
        // ② Redis第一道防线：检查缓存状态
        if (!tokenCacheService.isTokenValid(tokenId)) {
            logger.debug("Token invalid in cache: {}", tokenId);
            return false; // 黑名单或已过期
        }
        
        // ③ JWT第二道防线：验证签名和有效期
        Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token); // 抛出异常表示无效

        logger.debug("Token validation successful: {}", tokenId);
        return true;
    } catch (ExpiredJwtException e) {
        // Token过期
        logger.warn("Token expired: {}", e.getClaims().get("tokenId"));
    } catch (SignatureException e) {
        // 签名错误（被篡改）
        logger.warn("Invalid token signature");
    }
    return false;
}
```

**双重验证机制**：

```
请求到达
   ↓
① Redis检查（快速）
   ├─ 黑名单？      → 拒绝
   ├─ 缓存不存在？  → 拒绝
   └─ 存在且有效？  → 继续
   ↓
② JWT签名验证（安全）
   ├─ 签名错误？    → 拒绝（被篡改）
   ├─ 已过期？      → 拒绝
   └─ 验证通过？    → 放行
   ↓
设置SecurityContext
```

### 2.4 场景三：无感刷新 - Token自动续期

**业务场景**：用户正在编辑文档，Token还有3分钟过期

PaiSmart实现了**三层刷新机制**：

#### 层级1：过滤器自动刷新（主动）

```java
// JwtAuthenticationFilter.doFilterInternal()
String token = extractToken(request);
if (token != null && jwtUtils.validateToken(token)) {
    // 检查是否需要预刷新（剩余时间<5分钟）
    if (jwtUtils.shouldRefreshToken(token)) {
        String newToken = jwtUtils.refreshToken(token);
        if (newToken != null) {
            // 通过响应头返回新Token
            response.setHeader("New-Token", newToken);
            logger.info("Token auto-refreshed proactively");
        }
    }
    username = jwtUtils.extractUsernameFromToken(token);
}
```

**前端处理**：

```javascript
// 前端拦截器
axios.interceptors.response.use(response => {
    const newToken = response.headers['new-token'];
    if (newToken) {
        // 静默更新Token
        localStorage.setItem('token', newToken);
        console.log('Token已自动刷新');
    }
    return response;
});
```

#### 层级2：宽限期刷新（容错）

```java
public boolean canRefreshExpiredToken(String token) {
    Claims claims = extractClaimsIgnoreExpiration(token);
    if (claims == null) return false;
    
    long expirationTime = claims.getExpiration().getTime();
    long currentTime = System.currentTimeMillis();
    long expiredTime = currentTime - expirationTime;
    
    // 过期不超过10分钟，允许刷新
    return expiredTime > 0 && expiredTime < REFRESH_WINDOW;
}
```

**时间线示例**：

```
10:00  Token生成（有效期至11:00）
10:55  剩余5分钟 → 触发预刷新（层级1）
11:00  Token过期
11:05  宽限期内 → 仍可刷新（层级2）
11:10  宽限期结束 → 必须重新登录
```

#### 层级3：RefreshToken机制（备用）

```java
// 用户主动调用刷新接口
@PostMapping("/api/v1/auth/refreshToken")
public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
    // 验证RefreshToken（有效期7天）
    if (!jwtUtils.validateRefreshToken(request.refreshToken())) {
        return ResponseEntity.status(401)
            .body(Map.of("message", "Invalid refresh token"));
    }
    
    String username = jwtUtils.extractUsernameFromToken(request.refreshToken());
    
    // 生成新的Token和RefreshToken
    String newToken = jwtUtils.generateToken(username);
    String newRefreshToken = jwtUtils.generateRefreshToken(username);
    
    return ResponseEntity.ok(Map.of(
        "token", newToken,
        "refreshToken", newRefreshToken
    ));
}
```

### 2.5 场景四：用户登出 - Token黑名单

**业务场景**：用户点击退出登录

```java
public void invalidateToken(String token) {
    String tokenId = extractTokenIdFromToken(token);
    if (tokenId != null) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        long expireTime = claims.getExpiration().getTime();
        String userId = claims.get("userId", String.class);
        
        // ① 加入Redis黑名单
        tokenCacheService.blacklistToken(tokenId, expireTime);
        
        // ② 从有效Token缓存中移除
        tokenCacheService.removeToken(tokenId, userId);
        
        logger.info("Token invalidated: {}", tokenId);
    }
}
```

**Redis数据变化**：

```
登出前：
jwt:valid:a1b2c3d4e5f6789012345678 → {userId, username, ...}
jwt:user:123 → [a1b2c3d4e5f6789012345678]

登出后：
jwt:blacklist:a1b2c3d4e5f6789012345678 → 1735830000000 (时间戳)
jwt:valid:a1b2c3d4e5f6789012345678 → (已删除)
jwt:user:123 → [] (空集合)
```

---

## 📖 第三章：Spring Security过滤器链

### 3.1 SecurityConfig核心配置

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable()) // ① 禁用CSRF（前后端分离）
        .authorizeHttpRequests(authorize -> authorize
            // ② 静态资源：所有人可访问
            .requestMatchers("/", "/test.html", "/static/**").permitAll()
            
            // ③ WebSocket：允许匿名连接（Token在握手时验证）
            .requestMatchers("/chat/**", "/ws/**").permitAll()
            
            // ④ 登录注册：开放接口
            .requestMatchers("/api/v1/users/register", 
                           "/api/v1/users/login").permitAll()
            
            // ⑤ 普通用户接口：需要USER或ADMIN角色
            .requestMatchers("/api/v1/upload/**", 
                           "/api/v1/documents/**").hasAnyRole("USER", "ADMIN")
            
            // ⑥ 管理员专属接口
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            
            // ⑦ 其他请求：需要认证
            .anyRequest().authenticated())
        
        // ⑧ 无状态会话（不创建Session）
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        
        // ⑨ 添加自定义过滤器
        .addFilterBefore(jwtAuthenticationFilter, 
                        UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(orgTagAuthorizationFilter, 
                       JwtAuthenticationFilter.class);
    
    return http.build();
}
```

### 3.2 过滤器执行顺序

```
HTTP请求
   ↓
【过滤器1】JwtAuthenticationFilter
   ├─ 提取Token
   ├─ 验证Token有效性
   ├─ 检查是否需要刷新
   └─ 设置SecurityContext（用户身份）
   ↓
【过滤器2】OrgTagAuthorizationFilter
   ├─ 提取userId和role
   ├─ 检查组织权限（多租户）
   └─ 设置Request Attribute
   ↓
【Spring Security】授权检查
   ├─ 路径匹配：/api/v1/admin/** ?
   ├─ 角色检查：hasRole("ADMIN") ?
   └─ 通过/拒绝
   ↓
Controller处理请求
```

### 3.3 JwtAuthenticationFilter详解

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) {
        try {
            // ① 从请求头提取Token
            String token = extractToken(request); // "Bearer xxx"
            
            if (token != null) {
                String newToken = null;
                String username = null;
                
                // ② 验证Token有效性
                if (jwtUtils.validateToken(token)) {
                    // ③ 检查是否需要预刷新（剩余<5分钟）
                    if (jwtUtils.shouldRefreshToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        logger.info("Token auto-refreshed");
                    }
                    username = jwtUtils.extractUsernameFromToken(token);
                } else {
                    // ④ Token过期，检查宽限期
                    if (jwtUtils.canRefreshExpiredToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        username = jwtUtils.extractUsernameFromToken(newToken);
                        logger.info("Expired token refreshed");
                    }
                }
                
                // ⑤ 返回新Token给前端
                if (newToken != null) {
                    response.setHeader("New-Token", newToken);
                }
                
                // ⑥ 设置Spring Security上下文
                if (username != null) {
                    UserDetails userDetails = 
                        userDetailsService.loadUserByUsername(username);
                    
                    UsernamePasswordAuthenticationToken authentication = 
                        new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    
                    SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
                }
            }
            
            // ⑦ 继续过滤器链
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("Authentication error", e);
        }
    }
    
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7); // 去掉"Bearer "
        }
        return null;
    }
}
```

**实际请求示例**：

```http
GET /api/v1/documents/uploads HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

↓ 过滤器处理

HTTP/1.1 200 OK
New-Token: eyJhbGciOiJIUzI1NiJ9... (如果刷新了)
Content-Type: application/json

{
  "code": 200,
  "data": [...]
}
```

---

## 📖 第四章：Redis缓存服务

### 4.1 TokenCacheService架构

```java
@Service
public class TokenCacheService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀设计
    private static final String TOKEN_PREFIX = "jwt:valid:";      // 有效Token
    private static final String USER_TOKENS_PREFIX = "jwt:user:"; // 用户Token集合
    private static final String REFRESH_PREFIX = "jwt:refresh:";  // RefreshToken
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:"; // 黑名单
}
```

### 4.2 缓存Token信息

```java
public void cacheToken(String tokenId, String userId, 
                      String username, long expireTimeMs) {
    String key = TOKEN_PREFIX + tokenId;
    
    // 构建Token元数据
    Map<String, Object> tokenInfo = new HashMap<>();
    tokenInfo.put("userId", userId);
    tokenInfo.put("username", username);
    tokenInfo.put("expireTime", expireTimeMs);
    
    // 计算TTL（比JWT过期时间多5分钟缓冲）
    long ttlSeconds = (expireTimeMs - System.currentTimeMillis()) / 1000 + 300;
    
    // 存储到Redis
    redisTemplate.opsForValue().set(key, tokenInfo, ttlSeconds, TimeUnit.SECONDS);
    
    // 添加到用户Token集合（支持批量失效）
    addTokenToUser(userId, tokenId, expireTimeMs);
}
```

**Redis数据结构**：

```
String类型：
jwt:valid:a1b2c3d4...
  Value: {"userId":"123", "username":"zhangsan", "expireTime":1735833600000}
  TTL: 3900秒

Set类型：
jwt:user:123
  Members: ["a1b2c3d4...", "e5f6g7h8..."]  // 该用户的所有Token
  TTL: 自动过期
```

### 4.3 Token有效性检查

```java
public boolean isTokenValid(String tokenId) {
    // ① 检查黑名单
    if (isTokenBlacklisted(tokenId)) {
        return false;
    }
    
    // ② 检查缓存
    String key = TOKEN_PREFIX + tokenId;
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
}

private boolean isTokenBlacklisted(String tokenId) {
    String key = BLACKLIST_PREFIX + tokenId;
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
}
```

### 4.4 黑名单机制

```java
public void blacklistToken(String tokenId, long expireTimeMs) {
    String key = BLACKLIST_PREFIX + tokenId;
    
    // 计算剩余有效时间
    long ttlSeconds = Math.max(
        (expireTimeMs - System.currentTimeMillis()) / 1000, 0);
    
    if (ttlSeconds > 0) {
        // 只需缓存到原过期时间即可
        redisTemplate.opsForValue().set(key, 
            System.currentTimeMillis(), ttlSeconds, TimeUnit.SECONDS);
    }
}
```

**为什么不永久存储黑名单？**
- Token过期后自动失效，无需永久记录
- 节省Redis内存
- TTL自动清理

---

## 📖 第五章：多租户权限控制

### 5.1 组织标签（OrgTag）概念

PaiSmart采用**基于组织标签的多租户架构**：

```
企业A
├─ tech_dept (技术部)
│  ├─ 用户: zhangsan
│  └─ 文档: doc1.pdf, doc2.docx
├─ product_dept (产品部)
│  ├─ 用户: lisi
│  └─ 文档: design.pdf
└─ public (公开)
   └─ 文档: handbook.pdf
```

### 5.2 User模型设计

```java
@Entity
public class User {
    @Id
    private Long id;
    
    private String username;
    private String password;
    
    @Enumerated(EnumType.STRING)
    private Role role; // USER / ADMIN
    
    // 用户所属组织（可多个，逗号分隔）
    private String orgTags; // "tech_dept,product_dept"
    
    // 主组织（默认上传文档到这里）
    private String primaryOrg; // "tech_dept"
}
```

### 5.3 OrgTagAuthorizationFilter详解

```java
@Component
public class OrgTagAuthorizationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) {
        String path = request.getRequestURI();
        
        // ① 简单授权：只需用户ID（不检查特定资源权限）
        if (path.matches(".*/upload/chunk.*") || 
            path.matches(".*/documents/uploads.*")) {
            
            String token = extractToken(request);
            String userId = jwtUtils.extractUserIdFromToken(token);
            String role = jwtUtils.extractRoleFromToken(token);
            
            // 设置为Request Attribute，供Controller使用
            request.setAttribute("userId", userId);
            request.setAttribute("role", role);
            
            filterChain.doFilter(request, response);
            return;
        }
        
        // ② 资源权限检查：访问特定文档
        if (path.matches(".*/documents/([a-f0-9]{32}).*")) {
            String fileMd5 = extractFileMd5FromPath(path);
            
            // 从数据库查询文档
            Optional<FileUpload> fileUpload = 
                fileUploadRepository.findByFileMd5(fileMd5);
            
            if (fileUpload.isEmpty()) {
                response.setStatus(404);
                return;
            }
            
            FileUpload file = fileUpload.get();
            String token = extractToken(request);
            String userId = jwtUtils.extractUserIdFromToken(token);
            String role = jwtUtils.extractRoleFromToken(token);
            String userOrgTags = jwtUtils.extractOrgTagsFromToken(token);
            
            // ③ 权限判断
            boolean hasAccess = checkAccess(file, userId, role, userOrgTags);
            
            if (!hasAccess) {
                response.setStatus(403);
                response.getWriter().write("Access denied");
                return;
            }
        }
        
        filterChain.doFilter(request, response);
    }
    
    private boolean checkAccess(FileUpload file, String userId, 
                               String role, String userOrgTags) {
        // ① 管理员：全部可访问
        if ("ADMIN".equals(role)) {
            return true;
        }
        
        // ② 文档所有者：可访问
        if (file.getUserId().equals(userId)) {
            return true;
        }
        
        // ③ 公开文档：所有人可访问
        if (file.isPublic()) {
            return true;
        }
        
        // ④ 组织成员：可访问同组织文档
        if (userOrgTags != null && userOrgTags.contains(file.getOrgTag())) {
            return true;
        }
        
        return false;
    }
}
```

### 5.4 权限判断流程图

```
请求访问文档
   ↓
┌─────────────────┐
│ 是管理员？       │ ─Yes→ 允许访问
└─────────────────┘
   │ No
   ↓
┌─────────────────┐
│ 是文档所有者？   │ ─Yes→ 允许访问
└─────────────────┘
   │ No
   ↓
┌─────────────────┐
│ 是公开文档？     │ ─Yes→ 允许访问
└─────────────────┘
   │ No
   ↓
┌─────────────────┐
│ 同组织成员？     │ ─Yes→ 允许访问
└─────────────────┘
   │ No
   ↓
拒绝访问（403）
```

---

## 📖 第六章：完整认证流程实战

### 6.1 用户注册流程

```java
// UserController.java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    // ① 验证用户名是否已存在
    if (userRepository.findByUsername(request.username()).isPresent()) {
        return ResponseEntity.badRequest()
            .body(Map.of("message", "Username already exists"));
    }
    
    // ② 创建用户
    User user = new User();
    user.setUsername(request.username());
    user.setPassword(passwordUtil.encode(request.password())); // BCrypt加密
    user.setRole(User.Role.USER);
    user.setPrimaryOrg("DEFAULT"); // 默认组织
    user.setOrgTags("DEFAULT");
    
    userRepository.save(user);
    
    return ResponseEntity.ok(Map.of("message", "User registered successfully"));
}
```

### 6.2 用户登录流程

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request) {
    // ① 查询用户
    User user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new CustomException("User not found", 404));
    
    // ② 验证密码
    if (!passwordUtil.matches(request.password(), user.getPassword())) {
        return ResponseEntity.status(401)
            .body(Map.of("message", "Invalid credentials"));
    }
    
    // ③ 生成Token
    String token = jwtUtils.generateToken(user.getUsername());
    String refreshToken = jwtUtils.generateRefreshToken(user.getUsername());
    
    // ④ 返回Token和用户信息
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Login successful",
        "data", Map.of(
            "token", token,
            "refreshToken", refreshToken,
            "user", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "orgTags", user.getOrgTags(),
                "primaryOrg", user.getPrimaryOrg()
            )
        )
    ));
}
```

### 6.3 前端Token管理

```javascript
// 登录
async function login(username, password) {
    const response = await axios.post('/api/v1/users/login', {
        username, password
    });
    
    // 存储Token
    localStorage.setItem('token', response.data.data.token);
    localStorage.setItem('refreshToken', response.data.data.refreshToken);
    localStorage.setItem('user', JSON.stringify(response.data.data.user));
}

// 请求拦截器：自动添加Token
axios.interceptors.request.use(config => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

// 响应拦截器：自动更新Token
axios.interceptors.response.use(response => {
    const newToken = response.headers['new-token'];
    if (newToken) {
        localStorage.setItem('token', newToken);
        console.log('Token已自动刷新');
    }
    return response;
}, error => {
    // Token过期，跳转登录页
    if (error.response?.status === 401) {
        localStorage.clear();
        window.location.href = '/login';
    }
    return Promise.reject(error);
});
```

---

## 📖 第七章：安全最佳实践

### 7.1 密钥管理

```yaml
# application.yml
jwt:
  secret-key: ${JWT_SECRET_KEY:your-base64-encoded-secret-key}
```

**生成安全密钥**：

```java
// GenerateJwtKey.java
public class GenerateJwtKey {
    public static void main(String[] args) {
        SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
        System.out.println("JWT密钥: " + base64Key);
    }
}
```

### 7.2 防止JWT被窃取

1. **HTTPS传输**：防止中间人攻击
2. **HttpOnly Cookie**：防止XSS攻击（可选）
3. **短期有效期**：限制被盗Token的危害
4. **IP绑定**：检测异常登录位置
5. **设备指纹**：识别设备变更

### 7.3 防止重放攻击

```java
// 在JWT中添加随机nonce
claims.put("nonce", UUID.randomUUID().toString());

// Redis记录已使用的nonce（短期存储）
redisTemplate.opsForValue().set("nonce:" + nonce, "used", 5, TimeUnit.MINUTES);
```

---

## 🎯 模块总结与练习

### 核心知识点回顾

1. ✅ **JWT结构**：Header.Payload.Signature
2. ✅ **双重验证**：Redis缓存 + JWT签名
3. ✅ **三层刷新**：预刷新 + 宽限期 + RefreshToken
4. ✅ **黑名单机制**：主动失效Token
5. ✅ **过滤器链**：认证过滤器 → 授权过滤器
6. ✅ **多租户权限**：基于组织标签的数据隔离

### 实战练习题

**练习1：实现"记住我"功能**
- 提示：延长RefreshToken有效期至30天
- 思考：如何平衡安全性与便利性？

**练习2：实现单点登录（SSO）**
- 提示：使用Redis存储所有设备的Token
- 思考：如何实现"其他设备自动登出"？

**练习3：实现Token续期日志**
- 提示：记录每次Token刷新的时间和IP
- 思考：如何检测异常登录行为？

### 源码阅读清单

建议按以下顺序阅读源码，加深理解：

1. **JwtUtils.java** - JWT核心工具类（434行）
   - 重点方法：generateToken(), validateToken(), refreshToken()
   
2. **TokenCacheService.java** - Redis缓存服务
   - 重点方法：cacheToken(), isTokenValid(), blacklistToken()
   
3. **JwtAuthenticationFilter.java** - JWT认证过滤器
   - 重点方法：doFilterInternal()
   
4. **SecurityConfig.java** - Spring Security配置
   - 重点配置：authorizeHttpRequests(), sessionManagement()
   
5. **OrgTagAuthorizationFilter.java** - 组织权限过滤器
   - 重点方法：checkAccess()
   
6. **AuthController.java** - 认证控制器
   - 重点接口：/refreshToken
   
7. **UserController.java** - 用户控制器
   - 重点接口：/register, /login

### 调试技巧

**1. 查看JWT内容**（在线工具：https://jwt.io）

```
将Token粘贴到jwt.io，查看Payload内容：
{
  "tokenId": "...",
  "role": "USER",
  "userId": "123",
  "sub": "zhangsan",
  "exp": 1735833600000
}
```

**2. 查看Redis缓存**

```bash
# 连接Redis
redis-cli

# 查看所有JWT相关key
KEYS jwt:*

# 查看Token信息
GET jwt:valid:a1b2c3d4e5f6789012345678

# 查看用户的所有Token
SMEMBERS jwt:user:123

# 查看黑名单
KEYS jwt:blacklist:*
```

**3. 断点调试建议**

在以下位置设置断点：
- `JwtUtils.generateToken()` - 观察Token生成过程
- `JwtAuthenticationFilter.doFilterInternal()` - 观察请求过滤
- `TokenCacheService.isTokenValid()` - 观察Redis验证
- `OrgTagAuthorizationFilter.checkAccess()` - 观察权限判断

---

## 📚 扩展阅读

1. **JWT官方规范**：RFC 7519
2. **Spring Security官方文档**：https://spring.io/projects/spring-security
3. **Redis官方文档**：https://redis.io/documentation
4. **OWASP安全指南**：认证与会话管理
5. **《Spring Security实战》** - 深入理解Spring Security原理
6. **《Redis设计与实现》** - 理解Redis数据结构和持久化

---

## 🎓 下一步学习路径

**恭喜你完成模块一的学习！**🎉

现在你已经掌握了：
- ✅ JWT认证的完整实现
- ✅ Spring Security的配置与过滤器
- ✅ Redis在认证系统中的应用
- ✅ 多租户权限控制机制

**建议继续学习**：
- **模块二：文件上传与存储模块** - 学习分片上传、MinIO对象存储、断点续传
- **模块三：文档解析与处理模块** - 学习Apache Tika、Kafka异步处理
- **模块四：向量化与存储模块** - 学习Embedding、Elasticsearch向量检索

每个模块都建立在前一个模块的基础上，循序渐进地构建完整的RAG知识库系统！

---

**学习反馈**

如果你在学习过程中遇到问题，可以：
1. 查看项目中的单元测试代码（src/test目录）
2. 运行测试用例，观察实际执行流程
3. 使用日志追踪请求的完整生命周期
4. 参考README.md中的项目架构图

祝你学习愉快！💪


# Learning · 博客系统后端

一个基于 **Spring Boot 2.7 + MyBatis-Plus + MySQL + Redis** 的博客系统后端，共 **22 个 REST 接口**，覆盖用户认证、文章 / 分类 / 评论管理，并在业务功能之外实现了四层横切能力：**JWT 双 Token 鉴权、角色权限、逻辑删除、接口限流**，以及文章详情缓存与**六处 Redis 依赖的降级策略**。

> 这是一个 Java 后端学习项目（目标：2027 寒假后端实习）。代码按真实项目的做法写 —— 统一响应封装、全局异常处理、严格分层、缓存一致性、按层次区分降级方向。**不是教学 demo**：那些看起来"绕"的写法背后都有具体问题，取舍与踩坑都记在 `md/` 的设计文档里。

---

## 目录

- [技术栈](#技术栈)
- [功能一览（22 个接口）](#功能一览22-个接口)
- [技术亮点](#技术亮点)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [接口文档](#接口文档)
- [测试与验证](#测试与验证)
- [已知取舍](#已知取舍)

---

## 技术栈

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | **2.x，用 `javax.*` 而不是 `jakarta.*`** |
| JDK | 17 | |
| MyBatis-Plus | 3.5.5 | 分页插件、`@TableLogic` 逻辑删除 |
| MySQL | 8.x | 四张表，**不建物理外键** |
| Redis | — | 缓存 / Token / 限流，统一用 `StringRedisTemplate` |
| jjwt | 0.11.5 | HS256 双 Token |
| spring-security-crypto | Spring Boot 管理 | **只引 BCrypt，未引完整 security starter** |
| Lombok | 1.18.30 | provided |
| spring-boot-starter-validation | — | 参数校验 |
| Knife4j | 4.5.0 | 在线接口文档 UI（基于 springdoc-openapi-ui 1.7.0 / OpenAPI 3），访问 `/doc.html` |
| Docker | 29.x + Compose v5 | **多阶段构建**；`docker compose up -d --build` 一键起 MySQL + Redis + 应用 |

---

## 功能一览（22 个接口）

> **响应约定**：HTTP 状态码**一律返回 200**，业务状态放在响应体的 `code` 字段（`200` / `400` / `401` / `403` / `404` / `429` / `500`）。详见下方「统一响应」一节。

### 认证 `/auth`（4）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 注册，BCrypt 加密；**角色硬编码为普通用户** |
| POST | `/auth/login` | 登录，返回 **access + refresh 双 Token** |
| POST | `/auth/refresh` | 续期，**刷新令牌轮转**（一次性凭证，用过即废） |
| POST | `/auth/logout` | 登出，access 进黑名单 + 删除 refresh key |

- 登录失败时「用户不存在」和「密码错误」返回**完全相同**的提示与状态码，防止用户名枚举。

### 用户 `/user`（6）

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/user/{id}` | 自己 **或** 管理员 |
| GET | `/user/list` | **仅管理员**（分页，`pageSize` 上限 50，越界返 400） |
| PUT | `/user/nickname/{id}` | **仅本人** |
| PUT | `/user/password/{id}` | **仅本人**；校验旧密码，改完**强制下线** |
| PUT | `/user/role` | **仅管理员**；不能降级最后一个管理员 |
| DELETE | `/user/{id}` | 自己 **或** 管理员 |

### 文章 `/article`（5）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/article/{id}` | 详情，带作者昵称，**走 Redis 缓存** |
| GET | `/article/list` | 分页列表，按创建时间倒序，**只返回摘要不返回正文** |
| POST | `/article` | 发布，作者 id **从 JWT 取** |
| PUT | `/article/{id}` | 修改，非作者 403，并清理缓存 |
| DELETE | `/article/{id}` | 删除，非作者 403，并清理 detail / views / lock 三个 key |

### 分类 `/category`（4）

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/category/list` | 登录即可（分类数量有限，不分页） |
| POST | `/category` | **仅管理员**，重名返 400 |
| PUT | `/category/{id}` | **仅管理员**，查重时排除自己 |
| DELETE | `/category/{id}` | **仅管理员**，分类下有**有效文章**时拒绝删除 |

### 评论 `/comment`（3）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/comment` | 发表前**校验文章存在**（无外键，数据库不会拦） |
| GET | `/comment/list` | 分页，按时间倒序，带作者昵称（批量查询避免 N+1） |
| DELETE | `/comment/{id}` | 删除，非作者 403 |

---

## 技术亮点

### JWT 双 Token 鉴权

access（30 分钟）+ refresh（7 天）同密钥签发，靠 `type` claim 区分（**两处都要校验**）。refresh 以 `userId` 为 key 存 Redis（天然单端登录），值是 SHA-256 哈希；登出把 access 放进黑名单，**TTL = token 剩余有效期**（不是固定值）。签发时带 `jti` —— 修过「同一秒签发的两个 token 字节级相同 → 轮转静默失效」的缺陷。

### 角色权限：水平越权与纵向越权分开处理

`role` 字段**写进 JWT claim**，`@RequireRole` 注解 + 授权拦截器比对。关键在于区分两类越权：

| 类型 | 例子 | 资源特征 | 机制 |
| --- | --- | --- | --- |
| **水平越权** | A 删 B 的文章 | **有主** | 归属校验 → 403 |
| **纵向越权** | 普通用户删分类、拉用户列表 | **无主（全站资产）** | 角色校验 → 403 |

**归属校验解决不了纵向越权**（全站资产没有所有者可比对），**角色校验也解决不了水平越权**（两个普通用户 role 相同）。两者正交，缺一不可。

### 接口限流：滑动窗口 + Lua，且拦截器顺序有讲究

Redis ZSET + Lua 原子脚本实现滑动窗口，`@RateLimit(limit, window)` 声明式使用；已登录按 `userId`、未登录按 IP 分维度。

拦截器顺序 **JWT → 授权 → 限流**，三个理由：① 拿不到 `userId` 就只能按 IP 限流；② 拿不到 `role` 无法按角色设阈值；③ **没权限的请求不该消耗限流额度**（否则攻击者能用"注定 403 的请求"耗掉目标的额度，变成廉价的 DoS 放大器）。

### 逻辑删除

四张表统一 `deleted TINYINT NOT NULL DEFAULT 0` + 实体 `@TableLogic`，**Service 层一行没改** —— MyBatis-Plus 在执行器层自动改写 SQL（查询拼 `deleted=0`，`deleteById` 变 `UPDATE ... SET deleted=1`）。

### Redis 缓存：Cache Aside + 互斥锁防击穿

读：查缓存 → 命中返回 → miss 查 DB → 回填。写：**先更新 DB，再删缓存**（删而不是更新，因为并发下"更新缓存"会写脏且无法自愈）。

缓存失效瞬间用 `setIfAbsent` 做互斥锁，只让一个请求重建 —— 注意**锁必须包住 DB 查询**，否则等于没加。

**浏览量单独走 Redis 计数器**，因为缓存要求"命中时不查 DB"而浏览量要求"每次访问都写 DB"，两者诉求冲突；缓存里的 `viewCount` 一律存 `null`，读出来统一用 `INCR` 的结果填充，缓存 miss 时才**惰性回写** DB。

### 降级策略：六处 Redis 依赖，两个方向

Redis 挂掉时**不是一刀切**，按依赖的层次决定方向：

| 层次 | 方向 | 例子 |
| --- | --- | --- |
| 性能层 | **fail-open** 放行 | 缓存回源查 DB；限流直接放行 |
| 安全 / 认证层 | **fail-closed** 拒绝（503） | 黑名单读、refresh key 读写 |
| 认证层的**删除**操作 | **fail-open** | 登出时删 key 失败只记日志（删不掉不产生错误的成功语义） |

六处依赖两种方向**全部实测过**（把 Redis 端口改错来验证）。

### 统一响应：HTTP 200 + `body.code`

鉴权失败、参数错误、服务器出错，**HTTP 层看到的都是 200**，业务状态在响应体的 `code` 里。这个选择本身没有对错，但**必须贯穿到底** —— 前端判断成功失败只看 `code`。

### 其他

- **列表查询用 `wrapper.select(...)` 指定列**：文章正文是 `TEXT`，列表一页 10 条若 `SELECT *` 会把几十 KB 正文全拉出来，而用户根本没看。
- **跨表查作者昵称用批量查询**：整个列表接口只查两次数据库，与页大小无关（改在 `map` 里逐条查就是一页 11 次查询的 N+1）。
- **浏览量用 SQL 层自增 / Redis `INCR`**，绝不"先查出来加一再写回"（读-改-写三步会丢更新）。
- **更新用 `LambdaUpdateWrapper` 显式指定列**，避免 `updateById` 把没 set 的字段重置。
- **分页参数越界返 400 而不是静默截断** —— 把错误暴露给调用方比悄悄改变语义更安全。

---

## 项目结构

```
com.jiangpa
├── annotation      RequireRole、RateLimit            —— 权限 / 限流声明式注解
├── common          Result、PageResult、CacheKeys     —— 通用返回结构 + 缓存 key 常量
├── config          SecurityConfig、WebMvcConfig、
│                   MybatisPlusConfig、RedisLuaConfig
├── controller      接口层（只接参数、调 Service、包装 Result）
├── dto             接收请求参数（带校验注解）
├── exception       GlobalExceptionHandler、BusinessException
├── interceptor     JwtInterceptor、AuthorizationInterceptor、RateLimitInterceptor
├── mapper          XxxMapper extends BaseMapper<Xxx>
├── pojo            实体
├── properties      JwtProperties
├── service         接口
├── service.impl    实现
├── utils           JwtUtils、IpUtils
└── vo              返回给前端

src/main/resources/lua/rate_limit.lua   滑动窗口限流脚本
```

**分层约定**：Controller 不写业务逻辑；Service 失败时 `throw new BusinessException(code, "提示语")`、**不返回 Result**；Mapper 只管读写数据库。**权限规则只在一个地方表达**（`@RequireRole` 和 Service 归属校验叠加时，行为取决于执行顺序，极难排查）。

---

## 快速开始

### 方式一：Docker Compose（推荐）

不需要本机装 MySQL / Redis / Maven / JDK —— 三件套都在容器里。

```bash
cp .env.example .env        # Windows: copy .env.example .env
# 编辑 .env，填三个口令（JWT_SECRET 至少 32 字符）
docker compose up -d --build
```

启动后打开 **<http://localhost:8081/doc.html>**。

| 服务 | 宿主端口 | 说明 |
| --- | --- | --- |
| 应用 | **8081** | 与 `server.port` 一致 |
| MySQL | **3307** | ⚠️ 映射到 3307：宿主 3306 常被本机 mysqld 占用 |
| Redis | 6379 | |

**几个设计点**：

- `.env` 里是**容器内新库**的口令，和你虚拟机上那个库完全无关（`.env` 已被 gitignore）
- 容器内**不含任何 `application-local.yml`** —— 靠 `.dockerignore` 挡在构建上下文之外，配置全部走环境变量注入，**所以镜像里没有数据库密码**
- 首次启动会自动执行 `docker/mysql-init/01-schema.sql`，四张表直接建好
- 应用以**非 root 用户**运行（`uid=999(app)`），时区已设为 `Asia/Shanghai`
- 首个管理员仍需手工指定：
  `docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" learning -e "UPDATE tb_user SET role=1 WHERE username='你的账号';"`

```bash
docker compose ps            # 状态
docker compose logs -f app   # 应用日志
docker compose down          # 停止（数据保留）
docker compose down -v       # 停止并删数据卷（库会重建）
```

> ⚠️ **首次构建约 10–15 分钟**（容器内要重新下载全部 Maven 依赖）。之后只要 `pom.xml` 没变，这一层会命中缓存，`--build` 很快。

---

### 方式二：本地运行（IDEA）

#### 1. 环境要求

- JDK **17**（⚠️ 注意本机 `java -version` 可能是 8，IDEA 里要单独配 17）
- MySQL 8.x、Redis
- Maven（**仓库里没有 Maven Wrapper**，所以用 IDEA 打开最省事；命令行需自备 `mvn`）

#### 2. 建库建表

四张表：`tb_user`、`tb_article`、`tb_category`、`tb_comment`，建表语句见 **[`md/文章模块接口文档.md`](md/文章模块接口文档.md) 第二节**（已与库中实际结构逐字对齐）。

> ⚠️ 表名**带 `tb_` 前缀**；四张表都有 `deleted` 逻辑删除列；评论表用的是**复合索引** `idx_article_create`。

#### 3. 配置 `JWT_SECRET` 环境变量

值是 JWT 签名密钥，**至少 32 个字符**（HS256 要求 256 位，短了会抛 `WeakKeyException`）：

```powershell
(1..32 | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }) -join ''
```

配到系统环境变量里。**改完必须把 IDEA 完全退出再打开** —— Windows 上已运行的进程读不到新加的环境变量，只重启项目没用。

#### 4. 重建 `src/main/resources/application-local.yml`

数据库和 Redis 的账号密码都在这个文件里，**已被 `.gitignore` 忽略**（克隆下来是没有的，需要自己建）：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/learning?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 你的密码
  redis:
    host: 127.0.0.1
    port: 6379
    password: 你的Redis密码
    database: 0
    timeout: 3000ms
jwt:
  secret: ${JWT_SECRET}
  access-expiration: 30m
  refresh-expiration: 7d
  issuer: learning
```

> ⚠️ 两个容易踩的点：
> 1. Spring Boot **2.x 的 Redis 配置前缀是 `spring.redis.*`**，3.x 才改成 `spring.data.redis.*` —— 抄了 3.x 的教程不会报错，只会**静默用默认值**（一直连 localhost）。
> 2. **`spring.redis.timeout` 别调大**：实测 Redis 不可达时每次失败往返约 2 秒，而一次请求可能撞多次（登录要过限流 + 写 refresh key ≈ 5 秒）。超时设大 = Redis 抖动时整站被拖慢，生产建议几百毫秒级。

#### 5. 指定第一个管理员（**不做这步管理接口谁都打不开**）

注册接口硬编码 `role = 0`，这是 fail-safe 设计，所以第一个管理员只能手工指定：

```sql
UPDATE tb_user SET role = 1 WHERE username = '你的账号';
SELECT id, username, role, deleted FROM tb_user;   -- 确认
```

#### 6. 运行

IDEA 里直接运行 `LearningApplication`，端口 **8081**。

启动后打开 **<http://localhost:8081/doc.html>** —— 在线接口文档，可以直接在上面调试所有接口。

---

## 接口文档

### 在线文档（推荐先看）

服务跑起来后浏览器打开 **<http://localhost:8081/doc.html>**（Knife4j）。22 个接口按模块分组，
**可以直接填参数调试**：

1. 先调 `POST /auth/login`（body 传 `username` / `password`），从响应的 `data.accessToken` 取 token
2. 在**左侧菜单**里点 **Authorize** —— ⚠️ Knife4j 把它放在**左侧导航菜单**里，不在右上角
   - Knife4j 拼 `Bearer ` 是**幂等**的：没带前缀它会自动补，已经带了就原样用，**两种都行**
   - 但换到 `/swagger-ui/index.html`（原生 Swagger UI）时是**无条件**拼 `Bearer `，**在那里必须只粘 token**，否则会发出 `Bearer Bearer eyJ...`
3. 之后所有请求自动带 `Authorization` 头，可以调试受保护接口了

> - `POST /auth/refresh` 的 refreshToken 走**请求体**，不是这个头，Authorize 里的 token 对它无效
> - `POST /auth/logout` 反过来**要读这个头**（拿它去拉黑），所以调它之前得先 Authorize
> - accessToken 有效期 30 分钟，过期后返回「登录已过期」，重新登录再贴一次即可

文档由 controller 签名**自动生成**，不会和实现脱节。

### 设计文档（`md/`）

下面是**设计层面**的记录，解释"为什么这么写"、当初踩了什么坑，与上面的在线文档互补。
全部在 **`md/`** 目录下，设计与实测结果都是回填过的（每份设计文档末尾有「实现记录」一节）：

| 文档 | 内容 |
| --- | --- |
| [`用户模块接口文档.md`](md/用户模块接口文档.md) | 用户 6 接口 + 认证说明、状态码约定、权限矩阵 |
| [`文章模块接口文档.md`](md/文章模块接口文档.md) | **四张表的建表 SQL 与索引设计**、文章 5 接口 |
| [`分类与评论模块接口文档.md`](md/分类与评论模块接口文档.md) | 分类 / 评论 7 接口、跨模块行为约定 |
| [`JWT双Token实现设计文档.md`](md/JWT双Token实现设计文档.md) | ⭐ 双 Token 的类设计 + 12 条踩坑 + 验证清单 |
| [`角色权限设计文档.md`](md/角色权限设计文档.md) | 水平 / 纵向越权区分、权限矩阵、实现记录 |
| [`接口限流设计文档.md`](md/接口限流设计文档.md) | 四种限流算法对比、滑动窗口 + Lua、**六处降级实测表** |
| [`逻辑删除设计文档.md`](md/逻辑删除设计文档.md) | `@TableLogic` 改写规则、**唯一索引冲突的四种方案对比** |
| [`Redis缓存设计文档.md`](md/Redis缓存设计文档.md) | 缓存 key 设计、浏览量方案取舍、六组验证方法 |
| [`全局异常处理器文档.md`](md/全局异常处理器文档.md) | 五类异常的覆盖范围 |
| [`JWT鉴权拦截器文档.md`](md/JWT鉴权拦截器文档.md) | 拦截器职责、放行规则、踩坑清单 |
| [`Postman接口测试文档.md`](md/Postman接口测试文档.md) | 用户与认证模块测试用例 |
| [`文章模块Postman测试文档.md`](md/文章模块Postman测试文档.md) | 文章模块测试用例（含双账号权限测试） |
| [`分类与评论模块Postman测试文档.md`](md/分类与评论模块Postman测试文档.md) | 24 条用例（含并发重名、空值缓存、N+1 验证） |

项目全貌（已完成能力、关键设计决策逐条解释、环境坑清单）见 **[`LearningHANDOFF.md`](LearningHANDOFF.md)**。

---

## 测试与验证

### 单元测试

**51 个用例**，JUnit 5 + Mockito，**纯单元测试**（不启动 Spring 容器、不连数据库和 Redis），IDEA 里跑全绿：

| 测试类 | 用例 | 锁住什么 |
| --- | --- | --- |
| `JwtUtilsTest` | 13 | `type` 双向校验；**role 必须用 `Number.class` 取**（用 `String.class` 必抛异常，把"全站 401"的根因钉成断言）；篡改 / 换密钥必须验签失败；同一秒签发两个 token 必须不同（`jti`） |
| `CacheKeysTest` | 9 | **uri 必须参与限流 key 拼接**；key 格式无双冒号、全带 `learning:` 前缀 |
| `TokenServiceImplTest` | 17 | **四张降级表**：issue / refresh / isRevoked 的 Redis 失败 → 503；logout 删 key → fail-open、写黑名单 → 503 |
| `RateLimitInterceptorTest` | 12 | Redis 挂 / 脚本返空 → **fail-open 放行且不 NPE**；超限返 **429 而非 401**；key 按 userId / IP 分维度 |

**为什么优先测这些**：降级方向靠接口测试极难复现（要停 Redis、改端口、重启），而权限与降级逻辑**写错了大部分用例还是绿的** —— 只有把"该放行的"也写成断言才拦得住。

### 接口回归

分模块的 Postman 测试文档在 `md/` 下，按用例走一遍即可。写新用例时几类边界特别容易漏：**空表 / 空结果**（`IN ()` 语法错误只在空表时出现）、**短输入**（字符串截取越界）、**并发更新的字段**（更新后确认 `viewCount` 没被重置）、**权限分支必须用第二个账号**（同一账号永远改自己的东西，403 那条分支根本触发不到）。

### 缓存怎么验证

```
① 删掉 detail key 强制 miss
② GET /article/1 → 控制台【应该有】 SELECT
③ 立刻再 GET /article/1 → 控制台【不应该有】 SELECT          ← 命中验证
④ 对比：Redis 的 views 应 +1，而 DB 的 view_count 【不变】    ← 只动缓存不动库
```

**第 ③④ 步是关键判据** —— 两项同时成立，才说明真的走了缓存且没回源。

---

## 已知取舍

这些是**刻意的设计决策，不是缺陷**：

- **列表页浏览量滞后**：列表读 DB（约 30 分钟回写一次），详情读 Redis 计数器，两者会不一致。已决定接受 —— 详见 [`md/Redis缓存设计文档.md`](md/Redis缓存设计文档.md) 2.4。
- **逻辑删除后名字不可复用**：用户名 / 分类名的唯一索引覆盖物理行，删掉的名字再注册会撞索引，返回「数据不可复用！」。
- **评论不级联**：文章逻辑删除后，它的评论仍是 `deleted = 0` 留在表里（回收站思路，误删可恢复）。代价是数据会堆积。
- **昵称变更后缓存 TTL 内不一致**：`ArticleDetailVO` 含 `authorNickname`，而缓存 key 是文章 id，反查不出该用户有哪些文章被缓存。昵称变更极低频，接受 TTL 内不一致。
- **限流阈值是演示值**，非流量观测值。
- **改密码 / 改角色后 access token 在 ≤30 分钟内仍有效**（无状态的固有代价），但 refresh token 已立即失效、无法续期。
- **管理员可以自降**，由"不能降级最后一个管理员"的守卫兜住。

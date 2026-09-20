# HANDOFF — Learning 项目交接说明

> 最后更新：2026-09-19
> 项目路径：`C:\Users\ASUS\Desktop\Learning`
> 项目仓库：`jiangpa1/Learning`（分支 `main`）
> 笔记仓库：`jiangpa1/java-learning`（每日练习与知识库）
> 文档目录：`md/`
>
> **2026-09-19 第二轮**：完成「文档一致性收尾」（四表 DDL 按库对齐、逻辑删除验收项改正、列表页滞后落文档），并新增 `README.md`。详见第八节末尾。

---

## 一、这是什么

一个 Java 后端学习项目，作者是计算机系大三学生，目标是 2026 年寒假（约 12 月—次年 1 月）找 Java 后端实习。

形态上是一个**简易博客后端**：用户、认证、文章、分类、评论，文章详情带 Redis 缓存，另有逻辑删除、角色权限、接口限流三层横切能力，以及 **51 个单元测试**。**共 22 个接口**（认证 4 + 用户 6 + 文章 5 + 分类 4 + 评论 3）。

需要说明的是，这**不是教学 demo**。接口分层、统一响应封装、JWT 双 Token 鉴权、角色权限、逻辑删除、接口限流、全局异常处理、分页、跨表查询、并发更新、缓存与缓存一致性、降级策略这些都是按真实项目的做法来的，代码里刻意避开了不少新手写法。接手或复看时，下面第七节的「关键设计决策」是最需要先读的部分——那些看起来"绕"的写法是为了解决具体问题，不要顺手改回简单版本。

**2026-09-18 一天内完成四块改造**（详见第五节）：逻辑删除、角色权限、refresh 异常处理、接口限流。当天发现并修复了 **7 个真实缺陷**，其中 3 个是"接口不可用"级别，全部有实测记录。


---

## 二、技术栈

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | **2.x，不是 3.x**，下面很多坑都跟这个有关 |
| JDK | 17 | pom 里已设 `java.version=17` |
| MyBatis-Plus | 3.5.5 | |
| MySQL 驱动 | `com.mysql:mysql-connector-j`（8.0.33） | 坐标不能换回旧的那个 |
| MySQL | 8.x | 跑在虚拟机 `192.168.133.128:3306`，库名 `learning` |
| **Redis** | — | 同一台虚拟机 `192.168.133.128:6379`，**有密码**；`spring-boot-starter-data-redis` |
| jjwt | 0.11.5 | api / impl / jackson 三件套 |
| spring-security-crypto | 由 Spring Boot 管理 | **只引 crypto，没引完整 starter** |
| Lombok | 1.18.30 | provided |
| spring-boot-starter-validation | | 参数校验 |
| **Knife4j** | 4.5.0 | 接口文档 UI，访问 `/doc.html`；底层是 **springdoc-openapi-ui 1.7.0**（**不是 springfox**）。坐标必须用 `knife4j-openapi3-spring-boot-starter` —— 带 `jakarta` 的那个是给 Spring Boot 3 的 |

端口：`8081`（**2026-09-19 从 8080 改的**；`md/` 下各文档的 Base URL 与两份 Postman 文档已同步为 8081）

> ⚠️ **这台虚拟机是多个项目共用的**（上面还有海南麻将的库）。所以 Redis 的 key 一律带 `learning:` 前缀，避免撞 key。

---

## 三、跑起来之前必须做的五件事

**1. 配 `JWT_SECRET` 环境变量**

值是 JWT 的签名密钥，**至少 32 个字符**（HS256 要求 256 位，短了会抛 `WeakKeyException`）。生成方式：

```powershell
(1..32 | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }) -join ''
```

配在系统环境变量里。**改完必须把 IDEA 完全退出再打开**——Windows 上已经运行的进程读不到新加的环境变量，只重启项目没用。

**2. 重建 `application-local.yml`**

数据库和 Redis 的账号密码都在这个文件里，已被 `.gitignore` 忽略，换台机器克隆下来是没有的。格式（**已随双 Token 改造更新**）：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://192.168.133.128:3306/learning?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 你的密码
  redis:
    host: 192.168.133.128
    port: 6379
    password: 你的Redis密码
    database: 0
    timeout: 3000ms
jwt:
  secret: ${JWT_SECRET}
  access-expiration: 30m       # ★ 原来的 expiration 已拆成两个
  refresh-expiration: 7d
  issuer: learning
```

> **⚠️ 与旧版的差异**：原来的 `jwt.expiration: 3600000`（靠"纯数字被当毫秒"的隐式规则）已**拆成 `access-expiration` / `refresh-expiration`**，值为 Spring 的 Duration 格式（`30m` / `7d`）。写错会在启动时被 `JwtUtils.@PostConstruct` 拦下并抛出"缺少配置"。
>
> **⚠️ `spring.redis.timeout` 别调大**：实测 Redis 不可达时，每次失败往返约 2 秒，而一次请求可能撞多次（登录要过限流 + 写 refresh key → 约 5 秒）。超时设大 = Redis 抖动时整站被拖慢。生产建议几百毫秒级。

**3. 建表**

四张表：`tb_user`、`tb_article`、`tb_category`、`tb_comment`。建表语句见 `md/文章模块接口文档.md` 第二节。

**注意实际表名带 `tb_` 前缀**——那份文档里写的是 `category` / `comment`（无前缀），是早期的设计稿，**以库里的 `tb_category` / `tb_comment` 为准**。

三处必须的 DDL 改动（**2026-09-18 起，库里已应用**）：

```sql
-- ① 评论表复合索引
ALTER TABLE tb_comment ADD INDEX idx_article_create (article_id, create_time);
DROP INDEX idx_article_id ON tb_comment;      -- 被上一条最左前缀覆盖，冗余

-- ② 逻辑删除字段（四张表都要，NOT NULL DEFAULT 0）
ALTER TABLE tb_user     ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
ALTER TABLE tb_article  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
ALTER TABLE tb_category ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
ALTER TABLE tb_comment  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;

-- ③ 角色字段
ALTER TABLE tb_user ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '0=user 1=admin' AFTER nickname;
```

**4. ⚠️ 手工指定第一个管理员（不做则管理接口谁都打不开）**

注册接口硬编码 `role = 0`（普通用户），这是 fail-safe 设计。所以第一个管理员只能手工指定：

```sql
UPDATE tb_user SET role = 1 WHERE username = '你的账号';
SELECT id, username, role, deleted FROM tb_user;   -- 确认
```

**不做这一步，加完 role 后 `GET /user/list` 会返回 403（包括你自己）。**

**5. 确认虚拟机和两个服务都起来了**

```powershell
Test-Connection 192.168.133.128 -Count 1 -Quiet      # 应为 True
```

MySQL 和 Redis 都在这台虚拟机上。**它经常处于关机状态** —— 动手前先确认能连上，否则写完代码既建不了表也跑不起来测。


---

## 四、目录结构与分层约定

```
com.jiangpa
├── annotation      RequireRole、RateLimit            —— 权限 / 限流声明式注解
├── common          Result、PageResult、CacheKeys      —— 通用返回结构 + 缓存 key 常量
├── config          SecurityConfig、WebMvcConfig、MybatisPlusConfig、RedisLuaConfig
├── controller      接口层
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

src/main/resources/lua/rate_limit.lua   —— 滑动窗口限流脚本（★ 改它必须重新编译资源）
```

**三个拦截器的执行顺序（就是注册顺序，别调换）**：

```
JwtInterceptor（你是谁，fail-closed）
  → AuthorizationInterceptor（你能不能干，fail-closed）
    → RateLimitInterceptor（最后才决定让不让你干，fail-open）
```

**顺序的三个理由**：① 拿不到 `userId` 就只能按 IP 限流；② 拿不到 `role` 无法按角色设阈值；③ **没权限的请求不该消耗限流额度**（否则攻击者能用"注定 403 的请求"耗掉目标用户/IP 的额度，变成廉价的 DoS 放大器）。

**放行名单**（`WebMvcConfig`，2026-09-19 因接入接口文档而扩充）：

| 拦截器 | `excludePathPatterns` |
| --- | --- |
| `JwtInterceptor` | `/auth/**`、`/error`、**文档路径** |
| `AuthorizationInterceptor` | `/auth/**`、`/error`、**文档路径** |
| `RateLimitInterceptor` | 只有 `/error` —— **不需要放行文档路径** |

三条放行各自的理由：

- `/auth/**` 不放行 = 拿 token 的接口要求先带 token，**死锁**，永远登不进去
- `/error` 不放行 = 出错时 Spring Boot 内部转发到 `/error` 又被拦一次，**真实错误被 401 盖住**
- **文档路径不放行 = 打开 `/doc.html` 只会得到一段 401 的 JSON**（2026-09-19 实测确认过这个现象）

文档路径常量 `DOC_PATHS`（定义在 `WebMvcConfig`）：`/doc.html`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`。
**以后再加任何接口文档 / 框架自动注册的 handler，记得一并加进这个数组** —— 这是唯一会漏出放行名单的一类路径。

**分层铁律**（改代码时别破坏）：

- **Controller** 只做三件事：接参数、调 Service、用 `Result.success(...)` 包装。不写业务逻辑。
- **Service** 失败时 `throw new BusinessException(code, "提示语")`，**不返回 Result**。方法签名返回业务类型（`UserVO`、`List<UserVO>`、`Long`、`void`）。
- **Service 不依赖 Result**（各 Service 接口里都不该出现 `Result` 的 import）。
- **Service 接口不声明受检异常**。Jackson 的 `JsonProcessingException` 属实现细节，必须就地转成 `BusinessException` 或降级处理，不能污染接口签名。
- **Mapper** 只管读写数据库，不判断业务规则。
- **权限规则只在一个地方表达**。`DELETE /user/{id}` 的设计是「自己 **或** 管理员」，所以**不能在 Controller 上加 `@RequireRole`**（那会先按角色拦掉普通用户，Service 里的"删自己"分支永远走不到）。注解和 Service 校验叠加时，行为由执行顺序决定，极难排查。


---

## 五、已完成

### 认证（`/auth/**`）
- `POST /auth/register` 注册，密码用 BCrypt 加密后存；**硬编码 `role=0`**（see 角色权限一节）
- `POST /auth/login` 登录，返回**双 Token**（access + refresh + `expiresIn`）
- `POST /auth/refresh` 续期，**刷新令牌轮转**（一次性凭证，用过即废）
- `POST /auth/logout` 登出，**access 进黑名单 + 删 refresh key**
- 登录失败时"用户不存在"和"密码错误"返回**完全相同**的提示和状态码，防止用户名枚举

> **JWT 双 Token 细节见 `md/JWT双Token实现设计文档.md`**。要点：两 token 同密钥、靠 `type` claim 区分（两处都要校验）；refresh 以 `userId` 为 key 存 Redis（天然单端登录）、值是 SHA-256 哈希；黑名单 TTL = token 剩余有效期（不是固定值）；签发时带 `jti`（修过"同秒签发的两个 token 字节级相同 → 轮转静默失效"的缺陷）。
>
> **实测过的一处关键行为**：`refresh` 每次成功都把 TTL **重置为满 7 天**（`issue` 传的是常量而非剩余时间）→ 这实际是**滑动窗口续期**，活跃用户永不下线。想改成"登录后 7 天铁定过期"就把 TTL 换成 `getRemainingMillis(claims)`。

### 用户模块（`/user/**`）
- `GET /user/{id}` 查询单个 —— **自己 或 管理员**
- `GET /user/list` 用户列表（**已分页**：`PageQueryDTO`，`pageSize` 上限 50，**越界返 400 而不是截断**）—— **仅管理员**
- `PUT /user/nickname/{id}` 修改昵称 —— **仅本人**（管理员也不能改别人）
- `PUT /user/password/{id}` **修改密码** —— **仅本人**；校验旧密码 + 新旧不能相同 + 两次一致，**改完删 refreshKey 强制下线**
- `DELETE /user/{id}` 删除 —— **自己 或 管理员**
- `PUT /user/role` **改角色**（`{id, role}`，role 0=降级/1=升级）—— **仅管理员**；含"不能降级最后一个管理员"守卫 + 改角色即作废旧凭证
- 返回给前端的是 `UserVO`（含 `role`），**不含 password**

> **参数位置有三种风格**：`nickname` / `password` / `DELETE` 的 id 在**路径**；`role` 的 id 在**请求体**；
> `list` 的参数在**查询字符串**。接口文档里有对照表。


### 文章模块（`/article/**`）
- `GET /article/{id}` 详情，带作者昵称，**走 Redis 缓存**
- `GET /article/list` 分页列表，按创建时间倒序，**只返回摘要不返回正文**
- `POST /article` 发布，作者 id 从 JWT 取
- `PUT /article/{id}` 修改，非作者返回 403，**并清理缓存**
- `DELETE /article/{id}` 删除，非作者返回 403，**并清理 detail / views / lock 三个 key**

### 分类模块（`/category/**`）
- `GET /category/list` 分类列表，**不分页**（分类数量有限），按 id 升序，登录即可
- `POST /category` 新增 —— **仅管理员**，**先查重**（重名返回 400）
- `PUT /category/{id}` 修改 —— **仅管理员**，查重时**排除自己**（`.ne(Category::getId, id)`）
- `DELETE /category/{id}` 删除 —— **仅管理员**，**分类下有有效文章则拒绝删除**（返回具体篇数）

### 评论模块（`/comment/**`）
- `POST /comment` 发表，**发表前校验文章存在**（无外键，数据库不会拦）
- `GET /comment/list` 分页查询，按 `create_time` 倒序，带作者昵称
- `DELETE /comment/{id}` 删除，非作者返回 403

### Redis 缓存（2026-09-16 接入并验证）
- `GET /article/{id}` 走 **Cache Aside**：读缓存 → 命中返回 → miss 查 DB → 回填
- key：`learning:article:detail:{id}`（内容，TTL 30 分钟 ± 5 分钟随机）、`learning:article:views:{id}`（浏览量计数，不设 TTL）
- 空值哨兵 `__NULL__` 防穿透（TTL 2 分钟）
- 浏览量改 Redis `INCR`，缓存 miss 时**惰性回写** DB

### 逻辑删除（2026-09-18 接入并验证）
四张表统一 `deleted TINYINT NOT NULL DEFAULT 0` + 实体 `@TableLogic`，**Service 层一行没改** —— MP 在执行器层自动改写 SQL（查询拼 `deleted=0`，`deleteById` 变 `UPDATE SET deleted=1`）。

**踩到并记录的两个点**（详见 `md/逻辑删除设计文档.md`）：

1. **唯一索引与逻辑删除冲突**：`uk_user_name` / `uk_name` 覆盖**物理行**（含 `deleted=1`），而应用层查重只看 `deleted=0` → **删掉的名字永远无法复用**，报错还是笼统的"数据已存在！"。四种解法对比后**选「方案 D：明确接受不可复用」**（用户名唯一是常态行为），并把兜底文案改成 `数据不可复用！`。
2. **删除后必须清缓存**：`deleteArticle` 原来只清 detail + views，**漏了 lock**；更要紧的是 `views` key **不设 TTL**，不清就是**永久垃圾**。现在三个 key 一起清。
3. **重复删除返回 404 而非 200**：Service 第一行是 `selectById` 判存在，MP 自动过滤 `deleted=0` → 查不到直接 404，**走不到那条 UPDATE**。404 语义更准，保持。

### 角色权限（2026-09-18 接入并验证）

**背景**：修掉一个已实测的越权漏洞 —— 任意登录用户 `DELETE /user/{id}` 能删掉任何人（用户 id 自增，从 1 遍历可删光），`GET /user/list`、`DELETE /category/{id}` 同样无保护。

**机制**：`role` 字段（0=用户 1=管理员）**写进 JWT claim**，`@RequireRole(1)` 注解标在 Controller 方法上，`AuthorizationInterceptor` 读注解比对。

**为什么 role 放 JWT 而不是每请求查库**：零额外查询；代价是角色变更最长 30 分钟才生效（access 有效期）。缓解：**改角色时无条件删该用户的 refresh key**，让他无法续期。

**区分两类越权（关键设计）**：

| 类型 | 例子 | 资源特征 | 机制 |
| --- | --- | --- | --- |
| **水平越权** | A 删 B 的账号/文章/评论 | **有主** | 归属校验 → 403 |
| **纵向越权** | 普通用户删分类、拉用户列表 | **无主（全站资产）** | 角色校验 → 403 |

**归属校验解决不了纵向越权**（全站资产没有所有者可比对），**角色校验也解决不了水平越权**（两个普通用户 role 相同）。**两者正交，缺一不可。**

**权限矩阵**见 `md/角色权限设计文档.md` 第六节。详见该文档第十三节「实现记录」，那里记了 4 个只有测试才能暴露的缺陷。

### 接口限流（2026-09-18 接入并验证）
- **滑动窗口**（Redis ZSET + Lua 原子脚本），`@RateLimit(limit, window)` 注解声明
- 维度：**已登录按 `userId`、未登录按 IP**，key 含接口路径（`learning:limit:{维度}:{标识}:{uri}`）
- 超限返回 **`code=429`**（HTTP 仍 200）+ `X-RateLimit-Limit/Remaining/Reset` + `Retry-After` 头
- 阈值：login 10/分、register 5/分、refresh 20/分、发文章/评论 10/分、列表 120/分、用户列表 30/分

详见 `md/接口限流设计文档.md` 第十四节「实现记录」。

### Docker 化部署（2026-09-20 完成并实测）

**一句话**：`docker compose up -d --build` 一条命令起 MySQL + Redis + 应用，**不再依赖那台会关机/抽风的虚拟机**。

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | **多阶段**：`maven:3.9-eclipse-temurin-17` 构建 → `eclipse-temurin:17-jre` 运行。非 root（`uid=999(app)`）；`TZ=Asia/Shanghai`；ENTRYPOINT 用 exec 形式（java 成为 PID 1，`docker stop` 的 SIGTERM 才能触发优雅停机） |
| `docker-compose.yml` | 三个 service + 两个数据卷；mysql / redis 都带 healthcheck，app 用 `depends_on: condition: service_healthy` 等它们就绪 |
| `.dockerignore` | ★ **挡住 `src/main/resources/application-local.yml`** —— 否则会被 `COPY src` 带进构建阶段 → 打进 jar → 进最终镜像，**数据库密码跟着镜像走** |
| `application-docker.yml` | docker profile 专用：只写容器内拓扑（host 用 service 名 `mysql` / `redis`），口令全是 `${占位符}`，**可安全进 git 和镜像** |
| `docker/mysql-init/01-schema.sql` | 首次启动自动建四张表 |
| `.env` / `.env.example` | 口令文件；`.env` 已加进 `.gitignore`（**已用 `git check-ignore` 验证生效**） |

**三个必踩的坑（都实测过）**：

1. **`mvn package` 打出来的是瘦 jar** —— pom 里原本**没有 `<build>` 段、没有 `spring-boot-maven-plugin`**，产物只有 **0.1MB、连 `Main-Class` 都没有**，`java -jar` 报 `no main manifest attribute`，Docker 的 ENTRYPOINT 必然撞上。IDEA 里一直没暴露，因为 IDEA 直接跑 `main()` 配 `~/.m2` 的 classpath，**根本没碰这个 jar**。补上插件后 → **45.31MB**，`Main-Class: org.springframework.boot.loader.JarLauncher`、`Start-Class: com.jiangpa.LearningApplication`。
2. **`spring.profiles.active: local` 在镜像里没有对应文件** —— 会静默退回默认值（连 localhost）。所以 compose 里设 `SPRING_PROFILES_ACTIVE=docker` 并新增 `application-docker.yml`。
3. **宿主 3306 被本机 mysqld 占用**（实测 PID 10184）→ compose 里 MySQL 映射 **3307:3306**。

**实测验收（2026-09-20）**：

| 检查 | 结果 |
| --- | --- |
| 三个容器 | app `Up`、mysql `healthy`、redis `healthy` |
| 启动日志 profile | `The following 1 profile is active: "docker"` |
| `/doc.html`、`/v3/api-docs` | 200；**18 条 path = 22 个接口**、23 处接口级 `security` |
| 注册 → 登录 → 带 token 调 `/category/list` | 全 `code:200`（打通 JWT 验签 + Redis 黑名单 + 限流 Lua + MySQL） |
| 普通用户 `POST /category` | `403「无权限！」`（权限层生效） |
| **容器内 MySQL 行数** | **1**（正是刚注册那个）→ **证明连的是容器库、不是虚拟机那个**（那台上已有 4 个用户） |
| **镜像里有没有 `application-local.yml`** | **没有**；jar 内只有 `application.yml` + `application-docker.yml` |
| 容器内进程用户 | `uid=999(app)`（非 root） |

**两个已知可优化点（记下来，不是缺陷）**：

- **镜像 516MB** —— 因为 `eclipse-temurin:17-jre` 基础镜像本身就 430MB。想瘦到 ~250MB 可换 `eclipse-temurin:17-jre-alpine`。
- **首次构建 10–15 分钟**（实测 `dependency:go-offline` 单独用了 **849 秒**，容器内要重新下全部依赖）。之后只要 `pom.xml` 没变就命中 Docker 层缓存。想再快可给 `/root/.m2` 加 BuildKit cache mount。
- ⚠️ **本地 `mvn package` 出来的 jar 里是带 `application-local.yml` 的**（实测 `BOOT-INF/classes/application-local.yml` 确实存在）—— 所以**本地构建的 jar 不能外发**。Docker 这条路靠 `.dockerignore` 挡住了。

### 基础设施
- 三个拦截器（见第四节的顺序约定）
- **接口文档（Knife4j，2026-09-19 接入）**：`http://localhost:8081/doc.html` —— 由 controller 签名**自动生成**，22 个接口按模块分组、可在线调试。调试受保护接口的步骤：先调 `/auth/login` 拿 accessToken → 在**左侧菜单**的 **Authorize** 里填入
  - ⚠️ **每个 Controller 都必须加 `@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)`** —— **这是最关键的一条**：
    **Knife4j 只读「接口级」的 `security` 字段**（源码 `readApiSecurityOAS3` 判的是 `operation.hasOwnProperty("security")`），
    **它不会把根级的全局要求下发给各接口**。所以只配根级时，Knife4j 的 `securityKeys` 始终是 null →
    门禁 `t.api.securityFlag && securityKeys.includes(e.key)` 失败 → **请求里一根 `Authorization` 头都不加**，
    而 `JwtInterceptor` 返回的是**「未登录，请先登录」**（和"完全没带头"同一句话，极易误判成"没重启"）。
    ⚠️ **以后新增 Controller 一定要记得加这个注解**
  - ⚠️ `Knife4jConfig` 里的 `addSecurityItem(...)`（根级要求）**也要留着** —— 它负责让 **Swagger UI**（`/swagger-ui/index.html`）
    能挂上 token，并在文档层面声明鉴权。**但光有它 Knife4j 不生效** —— 两者分工不同，缺一不可
  - ⚠️ **Authorize 在 Knife4j UI 的左侧导航菜单里，不在右上角**（它是被当成一个菜单项 push 进导航列表的）
  - ⚠️ **scheme 的 key 必须就叫 `Authorization`** —— 这是第二个坑，和上一条症状完全相同：
    `type: http, scheme: bearer` 按 OpenAPI 规范**不带 `name` 字段**，springdoc 会把它丢掉，
    而 Knife4j 解析到 name 为空时会**拿 scheme 的 key 当请求头名字**兜底
    （源码 `strBlank(i.name) && (c.name=r, c.in="header")`，`r` 就是 key）。
    key 要是叫 `bearerAuth`，请求头会发成 **`bearerAuth: Bearer xxx`**，
    `JwtInterceptor` 找不到 `Authorization` → 返回**「未登录，请先登录」**。
    把 key 直接命名成 `Authorization`，兜底就拼对了，**同时保留 `Bearer ` 自动补全**
  - **排查口诀（三个 401 文案分别对应不同阶段，别混）**：
    | 响应 message | 含义 |
    | --- | --- |
    | `未登录，请先登录` | **压根没收到 `Authorization` 头** —— 头名错、根本没发、或**接口没声明 `security`（Knife4j 就不会加头）** |
    | `token 格式错误` | 收到头了，但缺 `Bearer ` 前缀（`JwtInterceptor` 第 55-59 行） |
    | `token 无效` | 头名和前缀都对，才轮到验签失败（篡改/换密钥） |
    | `登录已过期，请重新登录` | 签名合法，只是过期 |
    | `登录已失效，请重新登录` | 命中黑名单（已登出） |
  - 2026-09-19 实测对照：同一个假 token 只换头名 —— `bearerAuth:` → 「未登录，请先登录」；`Authorization:` → 「token 无效」
  - ⚠️ `@Operation(security = {})` 在 **springdoc 1.7.0 上无效**（不会输出 `security:[]`），所以 `/auth/**` 也会显示要求鉴权 —— **无害**：`/auth/**` 在放行名单里，且 `logout` 本来就需要这个头
  - Knife4j 拼 `Bearer ` 是**幂等**的（有无前缀都行）；但 **`/swagger-ui/index.html` 是无条件拼**，在那里必须只粘 token
- `GlobalExceptionHandler` 统一处理参数校验、唯一键冲突、业务异常、兜底异常
- `MybatisPlusConfig` 分页插件
- `Result` 支持 200 / 400 / 401 / 403 / 404 / 429 / 500（`overLimit` 为限流专用）
- `CacheKeys` 集中管理 key 前缀（`learning:`）、空值哨兵、限流 key

### 降级策略（四层依赖、两个方向，**全部实测过**）

| Redis 操作 | 层次 | Redis 挂了 | 实测结果 |
| --- | --- | --- | --- |
| 缓存（detail/views/lock） | 性能 | **fail-open** 回源查 DB | 接口正常 |
| 限流 | 性能 | **fail-open** 放行 | 连打 7 次全 200 |
| 黑名单**读**（`isRevoked`） | 安全 | **fail-closed** 503 | 503「认证服务暂时不可用」 |
| 黑名单**写**（logout） | 安全 | **fail-closed** 503 | 503 |
| refresh key **写**（issue） | 认证 | **fail-closed** 503 | 503「服务暂时不可用」 |
| refresh key **读**（refresh） | 认证 | **fail-closed** 503 | 503 |
| refresh key **删**（logout） | 认证 | **fail-open** 记录日志 | 登出照常返回 |

**判别规律**：性能层一律放行；安全/认证层的**读和写**要拒绝，但**删除类操作可以放行**（删不掉不产生错误的成功语义）。
**超时代价**：每次失败往返约 2 秒，一次请求可能撞多次（登录要过限流 + 写 refresh ≈ 5 秒）→ **`spring.redis.timeout` 必须设短**，否则 Redis 抖动会把整站拖死。


---

## 六、已修复的缺陷（保留作为教学案例）

> **本节原本标题是「进行中 / 已知缺陷」。2026-09-18 复核：下面三个缺陷已全部修复**，且 F5 之后又补了三条（见第六节之二）。保留原文是因为**这三个坑本身就是好素材**——面试讲"你怎么定位缓存击穿的问题"时可以直接用。

**① `selectArticleById` 的缓存击穿防护曾有实现缺陷**（2026-09-17 审查发现，2026-09-17 已修）：

1. ~~**锁加在 DB 查询之后**~~ → 击穿防护实际未生效，每个并发请求仍会查一次 DB
2. ~~**`wait(1000)` 会抛 `IllegalMonitorStateException`**~~ → `wait()` 必须在 `synchronized` 上下文里调用，该项目没有
3. ~~**递归调用没 `return`**~~ → `selectArticleById(id);` 的返回值被丢弃，该分支最终返回 `null`

**现状**：锁已包住 DB 查询（`cacheTryLock` 抢到锁后才 `fetchFromDbAndCache`）、等待改用 `Thread.sleep`、递归调用带 `return`。详见第七节第 14 条（那里记录的是**正确写法 + 当初错在哪**）。

### 第六节之二、2026-09-18 发现的七个缺陷（全部已修复并实测）

一天内做完四块改造，过程中发现 7 个真实缺陷，**其中 3 个是"接口不可用"级别**。这类"只有测试才能暴露"的问题价值最高：

| # | 缺陷 | 症状 | 根因 | 教训 |
| --- | --- | --- | --- | --- |
| 1 | `claims.get("role", String.class)` | **所有带合法 token 的请求返 401** | jjwt 类型 getter 精确比对，JSON 数字取 String 抛 `RequiredTypeException`（继承 `JwtException`），被拦截器兜底 catch 吃掉 | payload 取值类型必须和写入类型对齐，**用 `Number.class`** |
| 2 | 权限判断写成 `!A \|\| !B` | **自己看不了自己**（普通用户查自己 403、管理员查别人 403） | 要的是"A 或 B"，拒绝条件应为 `!A && !B` | 权限用例必须**成对测**（该拒的拒 + **该放的放**） |
| 3 | `@Valid` 漏写（`PUT /user/role`） | `role=9` / `role=-1` / 缺字段**全部 200 并写进 DB** | 约束注解只是元数据，**没有 `@Valid` 就不触发校验** | **发一个越界值验证是否被拒**；字段决定权限时，校验失效=权限模型被绕过 |
| 4 | 改角色后清 refresh key 的位置错 | **降级形同虚设**：旧 refresh 继续换新 token 并继承旧 role | 清 key 写在 `if (目标当前是管理员)` 内部 → 降级普通用户时不执行；写守卫 `throw` 之后 → 永远到不了 | 作废旧凭证属于"角色变更"这件事，**无条件执行**且放在守卫之后 |
| 5 | `LocalDateTime.now()` 传给 Lua 脚本 | **所有 `@RateLimit` 接口 500**（含登录/注册） | 脚本 `tonumber(ARGV[1])` 要毫秒数，传对象在参数序列化阶段就抛异常 | 判据：**Redis 里一个限流 key 都没有 = 脚本从未执行** |
| 6 | ZSET 的 member 也用时间戳 | **限流不触发**：阈值 5 时打 8 次全放行 | ZSET member 重复是**覆盖**不是新增 → 同毫秒请求被合并 → 计数偏少 | member 用 `now .. '-' .. math.random(1000000)`，score 保持纯时间戳 |
| 7 | `TokenServiceImpl` 三处 Redis 调用无降级 | Redis 挂时 login/logout/refresh 返 **500** | `issue`/`refresh`/`logout` 的 Redis 读写没包 try/catch | 按层次定方向：签发凭证 fail-closed 503；**删 key 可 fail-open**；**写黑名单必须 fail-closed** |

**另有 1 个安全缺陷**：`DELETE /user/{id}` 无归属校验（已实测能删任何人）→ 由角色权限改造一并修复。

> **面试素材**：这 8 条的共同特征是**"症状和根因隔得很远"** —— 症状是"登录 500"，根因是"Lua 参数类型"；症状是"限流偶尔不生效"，根因是"ZSET member 语义"。排查时最有用的一步是**查副作用是否存在**（Redis 里有没有 key、key 里几条），而不是盯着接口返回猜。


---

## 七、关键设计决策

**这一节最重要。** 下面每一条都是刻意的，看起来"多此一举"的写法背后都有原因。

### 1. HTTP 状态码一律返回 200

业务状态放在响应体的 `code` 字段里。**这意味着鉴权失败、参数错误、服务器出错，HTTP 层看到的都是 200。**

拦截器（`setStatus(SC_OK)`）和异常处理器都遵循这个约定，保持一致。前端判断成功失败要看 body 里的 `code`。

这个选择本身没有对错，但**必须贯穿到底**——如果哪天有人给某个接口单独设了真实的 HTTP 状态码，前端的两套判断逻辑就会打架。

### 2. 数据库不建物理外键

关联关系靠索引 + 应用层保证。这是互联网项目的普遍做法（《阿里巴巴 Java 开发手册》明确要求），外键会在写入时加锁、影响并发，分库分表后也无法维护。

**代价必须自己扛**：数据库不挡了，应用层就得校验。所以发表评论前要校验文章存在、删除分类前要统计引用数。

### 3. 列表查询用 `wrapper.select(...)` 指定列

```java
wrapper.select(Article::getId, Article::getTitle, Article::getSummary,
               Article::getUserId, Article::getViewCount, Article::getCreateTime)
```

**不要改成 `SELECT *`**。文章正文是 `TEXT`，列表一页查 10 条会把几十 KB 的正文全拉出来，这些数据用户根本没看。列表只给 `summary`，正文只在详情接口返回——这是"列表页轻量、详情页完整"的落地。

### 4. 跨表查作者昵称用批量查询，不要在循环里查

```java
List<Long> userIds = records.stream().map(Article::getUserId).distinct().toList();
Map<Long, String> nicknameMap = userMapper.selectBatchIds(userIds).stream()...
```

整个列表接口只查两次数据库，跟页大小无关。**如果改成在 `map` 里逐条 `selectById`，一页 10 条就是 11 次查询**——这就是 N+1 问题，是线上接口变慢最常见的原因。

另外注意这里有个**空集合判空**（`userIds.isEmpty() ? new HashMap<>() : ...`）。空表时如果不判空，拼出来的 SQL 是 `WHERE id IN ()`，MySQL 里是语法错误。

评论列表复用了同一套写法。

### 5. 浏览量用 SQL 层自增（已被 Redis 计数取代）

原来写法：

```java
wrapper.eq(Article::getId, id).setSql("view_count = view_count + 1");
```

**不能写成"先查出来加一再写回"**。后者是读-改-写三步，两个请求同时读到 43、各自加一都写回 44，实际该是 45——一次浏览凭空消失。这是更新丢失问题。

**2026-09-16 起改为 Redis `INCR`**（见第 12 条），原因见下条。

### 6. 更新用 `LambdaUpdateWrapper` 显式指定列

```java
wrapper.eq(Article::getId, id)
       .set(Article::getTitle, ...)
       .set(Article::getContent, ...)
       .set(Article::getUpdateTime, LocalDateTime.now());
```

**不要改回 `updateById`。** 实体里 `viewCount` 是基本类型（或者即使是包装类），新建一个对象只 set 要改的字段再 `updateById`，很容易把 `view_count` 重置成 0、或者意外覆盖 `create_time`。显式列出要更新的列，没列到的列根本不会出现在 SQL 里，最安全。

### 7. 作者 id 只从 JWT 取，绝不从请求体读

```java
public Result<?> addArticle(@Valid @RequestBody ArticleDTO dto,
                            @RequestAttribute("userId") Long userId) {
```

拦截器把 `userId` 塞进了 request attribute，Controller 用 `@RequestAttribute` 取。`ArticleDTO` 里**没有** `userId` 字段——如果允许前端传，任何人都能改个数字以别人名义发文。这是最典型的越权漏洞。评论模块同理。

### 8. 权限校验顺序：先判断存在，再判断归属

```java
Article article = articleMapper.selectById(id);
if (article == null) throw new BusinessException(404, "文章不存在");
if (!Objects.equals(article.getUserId(), userId)) throw new BusinessException(403, "无权操作他人文章");
```

**顺序不能反**。反了的话，改一篇不存在的文章会返回 403，让人以为是没有权限，排查时容易绕远路。同时反了还会 NPE——`article` 为 null 时 `getUserId()` 直接空指针。

### 9. 分页插件必须配置

`MybatisPlusConfig` 里的 `MybatisPlusInterceptor` + `PaginationInnerInterceptor`。

**没有它不是报错，而是静默失效**——LIMIT 不会拼进 SQL，查出全表再在内存里截取。数据少的时候完全看不出来，等表里几万条才会发现接口突然变慢。

### 10. 实体主键要标 `@TableId(type = IdType.AUTO)`

不标的话 MyBatis-Plus 会用默认的雪花算法在 Java 端生成 19 位 id，跟数据库的 `AUTO_INCREMENT` 对不上。功能和自增主键完全不同，回填回来的也是雪花值。

### 11. 缓存用 Cache Aside，更新时**删**缓存而不是更新缓存

读：查缓存 → 命中返回 → miss 查 DB → 回填。
写：**先更新 DB，再删缓存**。

**为什么删而不是更新**（三条）：

1. **更新缓存在并发下会写脏且无法自愈。** 两个请求并发改同一条数据，DB 依次变成 v1、v2，但"写缓存"的到达顺序可能反过来——缓存里留下 v1，与 DB 长期不一致，直到 TTL 过期才纠正。"删除"是幂等的，谁先谁后结果一样。
2. **避免无效更新。** 写时更新缓存意味着每次都产生一次缓存写，但这份数据可能压根没人读。
3. **降低更新成本。** 缓存值往往是多表聚合的结果（文章详情要拼作者昵称），"更新"就得重新算一遍全部聚合。

**已接受的代价**：`ArticleDetailVO` 里含 `authorNickname`，用户改昵称时**不知道该失效哪些缓存**（key 是文章 id，反查不出该用户有哪些文章被缓存了）。**选择接受 TTL 内的不一致** —— 昵称变更极低频，为它引入反向索引或双缓存不值得。

### 12. 浏览量迁到 Redis，缓存 miss 时惰性回写

**为什么不能把 `viewCount` 一起缓存**：缓存的目标是命中时不查 DB，而浏览量要求每次访问都写 DB——**两个诉求直接冲突**。若把 `viewCount` 缓存进去，命中时浏览量就不再累加，只有 TTL 过期那一次 +1，会严重少计。

**所以缓存里的 `viewCount` 一律存 `null`**，读出来后统一用 Redis 计数器填：

```
每次访问：读 detail 缓存 → 命中返回内容 → INCR views:{id} → 填进返回对象
缓存 miss：查 DB → setIfAbsent(views:{id}, DB 的 view_count) → INCR → 回写 DB → 回填缓存
```

**两个关键点，不要改**：

- **播种必须用 `setIfAbsent` 而不是 `set`。** miss 时从 DB 读到的是**旧值**，而 Redis 里可能已累积到很大（如 10000）。用 `set` 会把 Redis 覆盖成旧值，**累积的浏览当场全丢**。`setIfAbsent` 只在 key 不存在时写入，天然幂等。
- **`views` key 不能设 TTL（或必须远长于 detail 的 TTL）。** 因为命中分支是直接 `increment` 而没有播种逻辑——key 一旦过期，`increment` 会从 0 创建并返回 1，**把真实计数抹掉**。

**回写时机与代价**：只在缓存 miss（约 30 分钟一次）时回写，所以 1 万次访问可能只写 2 次库。代价是：① 回写节奏跟着读流量走，冷文会长期滞后（但会自愈）；② **Redis 崩溃会丢一个周期的增量**。浏览量这种非关键计数可以接受，**换成订单金额就绝不能这么做**。

### 13. 评论表要加复合索引，并删掉被覆盖的单列索引

```sql
ALTER TABLE tb_comment ADD INDEX idx_article_create (article_id, create_time);
DROP INDEX idx_article_id ON tb_comment;   -- 被上一条的最左前缀完全覆盖，冗余
```

查询是 `WHERE article_id = ? ORDER BY create_time DESC`。单列索引只能过滤，之后 MySQL 还要 filesort 排序；复合索引（等值列在前 + 排序列在后）让过滤和排序一次扫描完成。

`EXPLAIN` 验证（用 `IGNORE INDEX` 可在同一张表上对比）：

| 写法 | `key` | `Extra` |
| --- | --- | --- |
| 有复合索引 | `idx_article_create` | **`Backward index scan`**（无 filesort） |
| `IGNORE INDEX (idx_article_create)` | `idx_article_id` | **`Using filesort`** |

**加完复合索引要把被覆盖的单列索引删掉**——索引不是越多越好，每个索引都要在写入时同步维护。

### 14. 缓存击穿要用互斥锁，且锁必须包住 DB 查询

缓存失效瞬间，N 个并发请求会同时查 DB。用 `setIfAbsent` 做互斥锁，只让一个请求去重建：

```java
String lockKey = "learning:lock:article:detail:" + id;
Boolean locked = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
if (Boolean.TRUE.equals(locked)) {
    try {
        // ★ 抢到锁之后才查 DB —— 锁必须包住 DB 查询，否则等于没加
        Article article = articleMapper.selectById(id);
        ...组装 VO、自增浏览量、回填缓存、回写 DB
        return vo;
    } finally {
        stringRedisTemplate.delete(lockKey);   // ★ 必须 finally，否则异常就死锁
    }
} else {
    // 没抢到：短暂等待后重试读缓存
}
```

**四个坑，一个都不能漏**：

| 坑 | 后果 |
| --- | --- |
| **锁必须包住 DB 查询** | 锁加在 DB 查询之后 → 每个并发请求仍查一次 DB，**击穿防护完全失效** |
| 锁必须设过期时间 | 进程崩了 → 死锁，这个 key 永远重建不了 |
| 解锁必须放 `finally` | 抛异常就死锁 |
| 重试要限制次数 | 递归重试不加限制 → `StackOverflowError` |

**另外两个 Java 基础点**：

- **等待用 `Thread.sleep(毫秒)`，不是 `wait(毫秒)`。** `wait()` 是 `Object` 的方法，**必须在 `synchronized` 上下文里调用**，否则抛 `IllegalMonitorStateException`。`sleep()` 是 `Thread` 的静态方法，不需要锁。
- **递归重试必须 `return` 递归调用的结果。** `selectArticleById(id);` 这样写返回值会被丢弃，方法最终返回 `null`，`Result.success(null)` 直接发给前端。

> 想清楚互斥锁的**代价**：抢不到锁的请求要等待，增加了响应时间。所以工业界还有「逻辑过期」方案——不设 Redis TTL，把过期时间放进 value，发现逻辑过期就返回旧值 + 异步重建。两种方案要能对比着讲。

### 15. 分页参数越界要**拒绝**，不要静默截断

`GET` 的 query 参数用 **`@ModelAttribute` + `@Valid`** 绑定到 `PageQueryDTO`（**不是 `@RequestBody`** —— GET 请求没有请求体）：

```java
// Controller
public Result<?> selectList(@Valid @ModelAttribute PageQueryDTO pageQueryDTO)

// PageQueryDTO
@Min(value = 1, message = "从第一页开始访问")        private Integer pageNum = 1;
@Min(value = 1, ...) @Max(value = 50, ...)          private Integer pageSize = 10;
```

**行为**：`?pageSize=999` → **400**（不是按 50 返回 200）。

| 方案 | 行为 | 取舍 |
| --- | --- | --- |
| **拒绝**（本项目） | 400 + 明确提示 | 调用方立刻发现参数写错；代价是前端必须自己限住 |
| 静默截断（早期） | 按 50 返回 200 | 宽容；代价是调用方**不知道自己被截了**，可能误以为只有 50 条数据 |

**为什么改**：把错误暴露给调用方，比悄悄改变语义更安全。

> **⚠️ 这里藏着一个非常容易踩的坑**：`@RequestBody` 校验失败抛 `MethodArgumentNotValidException`，
> 而 **`@ModelAttribute` 校验失败抛的是 `BindException`**（前者是后者的子类）。
> 全局异常处理器里**两个都要有**，少一个就会掉到兜底返 **500** ——
> 表现为"服务器开小差了"，看起来像服务端故障，实际是客户端参数不合法。

### 16. 跨模块删除一律**不级联**（明确的取舍，不是遗漏）

数据库不建物理外键，所以"删除的连带影响"**只能靠应用层决定**，就必须显式约定：

| 关系 | 决策 | 行为 |
| --- | --- | --- |
| 用户 → 文章 / 评论 | **不级联** | 用户逻辑删除后，他的文章仍在、列表照常返回；只是查不到作者 → 昵称兜底成 `"未知作者"` |
| 文章 → 评论 | **不级联** | 文章逻辑删除后，它的评论仍是 `deleted = 0` 留在表里；访问 `/comment/list?articleId={已删文章}` 返 **404**（列表接口先校验文章存在） |
| 分类 → 文章 | **拒绝删除** | 分类下有**有效文章**时不允许删分类，返回具体篇数 |

**为什么不级联**：这是**回收站思路** —— 误删时可以恢复（直接改 `deleted` 字段），一旦级联就不可逆。
**代价**：数据会堆积，且"评论查不到但实际存在"对排查者不直观。

### 17. 改密码 / 改角色后必须**删 refreshKey** 强制下线

凭证有两层，失效行为不同：

| 凭证 | 处置 | 效果 |
| --- | --- | --- |
| **refreshToken** | 删 `learning:token:refresh:{userId}` | ✅ **立即失效**，无法续期 |
| **accessToken** | 无状态，改不了 | ⚠️ 剩余有效期（≤30 分钟）内**仍可用** |

**这解决了一个真实缺陷**：role 写在 JWT claim 里，而 `refresh` 是**从旧 token 的 claims 取 role**
再签发新 token —— 所以只要旧 refreshToken 还能用，**旧角色就会被无限续期**，"降级"等于没降
（实测过：修复前降级普通用户后，用降级前的 refreshToken 仍能换出带旧 role 的新 token）。

**已知残留代价**：access 的 ≤30 分钟窗口是"无状态"的固有代价。
想立即失效需要引入"改密/改角色时间戳 + 拦截器比对 token 的 `iat`"。

### 18. 首任管理员必须**手工指定**，且允许自降

- 注册接口**硬编码 `role = 0`**（fail-safe：忘了赋值的后果是"权限不足"而不是"人人都是管理员"）
- 所以第一个管理员只能手工 `UPDATE tb_user SET role = 1 WHERE username = 'xxx'`，
  **不做这一步，加完 role 后 `GET /user/list` 会返 403（包括你自己）**
- `PUT /user/role` **允许管理员降级自己**，由"**不能降级最后一个管理员**"的守卫兜住
  （守卫判断"除目标之外还有没有管理员"，用 `.ne(User::getId, id)` 排除目标自己）


---

## 八、待办清单

**2026-09-19 复核**：原清单里「用户列表分页」「请求体解析异常」「文章分类校验」「改密码接口」「分页参数抽公共组件」「单元测试」**六项已完成**，已从下方表中移除（明细见本节末尾）。

### 仍然待办

> **2026-09-20 复核**：「接口文档一致性收尾」与 **Docker 部署**均**已完成**（明细见本节末尾与第五节）。现在真正要动手的只剩**单元测试**一条。

| 优先级 | 事项 | 说明 |
| --- | --- | --- |
| — | ~~Docker 部署~~ | **2026-09-20 已完成并实测**，见第五节「Docker 化部署」 |
| **中** | **继续铺单元测试** | 优先 `UserServiceImpl` 的权限判断、`ArticleServiceImpl` 的缓存降级。见第十四节第 3 条 |
| 低 | 提示语统一 | 见第九节（**昵称兜底文案现有 4 处不一致**） |
| 低 | `JwtProperties` 写法 | 仍是 `@Component` + `@ConfigurationProperties`，未用 `@EnableConfigurationProperties`（能用，只是不够现代） |
| — | 限流阈值调优 | 当前值都是文档 7.2 的**演示值**，非流量观测值。**已在文档标注，不打算改** |

### 2026-09-19 完成明细

| 事项 | 落点 |
| --- | --- |
| 用户列表分页 | `GET /user/list` 改 `PageResult`，`wrapper.select` 只查轻量列 |
| 请求体解析异常处理 | `GlobalExceptionHandler` 补 `HttpMessageNotReadableException` / `HttpMediaTypeNotSupportedException` → 400 |
| 文章分类关联校验 | `categoryId` **非 null 时**才校验存在性（分类是可选的，与 `category_id` 允许 NULL 一致）；用 `selectById` 校验，受 `@TableLogic` 影响 → 指向已删除分类也会被拒 |
| **改密码接口** | `PUT /user/password/{id}`：校验旧密码 → 四个拒绝分支 → 落库 → **删 refreshKey 强制下线**（见第七节第 17 条） |
| **分页参数抽公共组件** | `PageQueryDTO`（`@Min`/`@Max`）+ 三个 Controller 统一 `@Valid @ModelAttribute`；`PageResult<?>` 通配符改成具体泛型 |
| **单元测试** | 4 个测试类 **51 个用例**（`JwtUtilsTest` 13 / `CacheKeysTest` 9 / `TokenServiceImplTest` 17 / `RateLimitInterceptorTest` 12），重点锁住**降级方向**与已修缺陷 |
| 昵称/密码接口拆分 | `UserUpdateDTO` → `UpdateNicknameDTO`，路径改为 `PUT /user/nickname/{id}`（昵称和密码的校验规则完全不同，混一个 DTO 会让改昵称也被要求传旧密码） |
| 三类参数异常处理 | 补 `BindException`（`@ModelAttribute` 校验失败）、`MissingServletRequestParameterException`、`MethodArgumentTypeMismatchException` → 全部 400（**少了会返 500**，见第七节第 15 条的坑） |
| 接口文档重写 | `md/用户模块接口文档.md` 按实际实现重写（10 个接口 + 8 个状态码 + 越权机制 + 跨模块行为约定） |
| `GlobalExceptionHandler` 注释 | 每个 handler 加 Javadoc：触发场景、返回码、和相邻 handler 的区别 |


### 2026-09-19 文档一致性收尾（同日第二轮）

| 事项 | 落点 |
| --- | --- |
| **四张表 DDL 按库对齐** | `md/文章模块接口文档.md` 第二节：表名全部加 **`tb_` 前缀**（原来是无前缀的设计稿）；用 `SHOW CREATE TABLE` 补上四表的 `deleted` 列、`tb_user` 的 `role` / `avatar`、评论表改**复合索引** `idx_article_create`（并记下 `DROP INDEX idx_article_id`） |
| 分类/评论文档 DDL 同步 | `md/分类与评论模块接口文档.md` 第二节：同样补 `deleted` + 复合索引；**删掉已失效的"命名提醒"**；「数据现状」快照按 SQL 重新核对 |
| 逻辑删除验收项改正 | `md/逻辑删除设计文档.md` 6.1 第⑤条正文由"仍返 200"改为 **404**（10.1 保留"预判与实测不符"的记录作为素材） |
| 列表页滞后落文档 | `md/Redis缓存设计文档.md` 新增 **2.4「已接受的代价：列表页的浏览量会滞后」** |
| **新增 `README.md`** | 仓库根目录：22 接口清单、技术亮点、项目结构、快速开始（`JWT_SECRET` + `application-local.yml` 重建步骤）、文档索引、测试说明、已知取舍 |

> 这一轮**没有改任何 Java 代码和数据库结构**，只是让文档与实现一致；同时复核出第九节里两条"小瑕疵"其实已经修掉了（见下）。


---

## 九、已知的小瑕疵

**提示语不统一。** 同一个意思有几种写法：

- 文章不存在：详情接口抛 `"文章不存在！"`（全角感叹号），修改和删除抛 `"文章不存在"`（无标点）
- 用户名已存在：Service 里查重抛 `"用户名已存在!"`（半角）
- **作者昵称兜底文案有四处不统一**：`ArticleServiceImpl` 的 `toMap`（136 行）是 `"默认昵称"`、`getOrDefault`（144 行）是 `"未知作者"`；`CommentServiceImpl` 的 `toMap`（78 行）是 `"默认昵称"`、`getOrDefault`（86 行）是 `"未知"`。建议统一成两个语义清晰的常量：**用户存在但昵称为空** → 一个文案；**用户查不到（脏数据）** → 另一个文案。
- `PUT /user/role` 校验失败时文案是 `"权限值只能是 0(降级) 或 1(升级)"` —— 这条已统一，可作为其他文案改写的参考

> ~~唯一键冲突兜底返回"数据已存在！"~~ —— **已改为 `"数据不可复用！"`**（2026-09-18，配合逻辑删除：同一个处理器要服务 user 和 category 两张表，文案不能偏向任何一方）。

**~~`PageResult` 有个没用的五参数构造器。~~** —— **已删除**（2026-09-19 复核：类里现在只有 `@Data` + 5 个字段，没有显式构造器）。

**JwtProperties 用 `@Component` + `@ConfigurationProperties` 绑定。** 能用，但更现代的写法是 `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan`。

**~~`selectArticleById` 里保留了大段注释掉的旧实现。~~** —— **已删除**（2026-09-18）。

**~~魔法数字散落。~~** —— **已提取常量**（2026-09-19 复核：`ArticleServiceImpl` 顶部已有 `DETAIL_TTL_SECONDS` / `TTL_JITTER_SECONDS` / `NULL_TTL_MINUTES` / `LOCK_TTL_SECONDS` / `RETRY_WAIT_MILLIS` 五个常量）。

**`RateLimitInterceptor` 的两处小瑕疵**：`getMethodAnnotation` 取了两次（第二次的判空是死代码）；`X-RateLimit-*` 头现在正常响应和超限响应都带（已修），但 `Retry-After` 在成功响应里也返回了"距窗口重置还有多久"，语义上略微奇怪（无害）。


---

## 十、环境相关的坑

这些都是实际踩过的，复发概率高：

| 现象 | 原因 |
| --- | --- |
| 启动报 `Could not resolve placeholder 'JWT_SECRET'` | 环境变量没配，或配了但 IDEA 没完全重启 |
| 启动报 `WeakKeyException` | `JWT_SECRET` 短于 32 字符 |
| 连不上 MySQL / Redis | 虚拟机 `192.168.133.128` 没开机。**它经常是关着的** |
| Redis 报 `NOAUTH Authentication required` | 没配 `spring.redis.password` |
| Redis 配置不生效、一直连 localhost | 前缀写错。**2.x 是 `spring.redis.*`，3.x 才改成 `spring.data.redis.*`**，抄了 3.x 的教程不会报错，只会静默用默认值 |
| 缓存里的值是乱码 | 用了 `RedisTemplate` 而非 `StringRedisTemplate`，默认走 JDK 二进制序列化 |
| 序列化报 `InvalidDefinitionException`（`LocalDateTime`） | 自己 `new ObjectMapper()` 了。必须注入 Spring 容器里的那个（已注册 `JavaTimeModule`） |
| Redis 报 `ERR value is not an integer or out of range` | 对一个非数字的值执行了 `INCR`。常见于 `String.valueOf(null)` 得到字符串 `"null"` 被播种进计数 key |
| `Could not find or load main class main.java.org.example.Xxx` | IDEA 的运行配置还指向旧包名，去 Run → Edit Configurations 改回 `com.jiangpa.Xxx` |
| 报 `Table 'learning.tb_user' doesn't exist` | 表名是 `tb_user`，不是 `user` |
| 登录一直提示密码错误但密码是对的 | 表里那条记录是早期用 MD5 存的，BCrypt 的 `matches` 对 MD5 串只会返回 false，清掉重新注册 |
| Maven 报 `'dependencies.dependency.version' ... is missing` | MySQL 驱动坐标用了旧的 `mysql:mysql-connector-java`，Spring Boot 2.7.8 起改成了 `com.mysql:mysql-connector-j` |
| 一堆 `javax.servlet` 找不到符号 | 抄了面向 Spring Boot 3 的教程。**2.7 用 `javax`，不是 `jakarta`** |
| 拦截器报 `SignatureException` 捕获不到 | jjwt 有两个同名类，要用 `io.jsonwebtoken.security.SignatureException`，`io.jsonwebtoken` 包下那个已废弃 |
| **所有带 token 的请求返 401「token 无效」** | jjwt 的 `claims.get(name, Class)` 是**精确类型比对**。payload 里 role 是 JSON 数字，用 `String.class` 取会抛 `RequiredTypeException`（继承 `JwtException`）→ 被拦截器兜底 catch 吃掉。**用 `Number.class`** |
| **`@RateLimit` 接口全部 500**（含登录） | 把 `LocalDateTime.now()` 对象传给 Lua 脚本参数（脚本要 `tonumber`）→ 参数序列化阶段就抛异常。**判据：Redis 里一个限流 key 都没有 = 脚本从未执行**。传 `System.currentTimeMillis()` |
| **限流不触发/阈值不准** | ZSET 的 member 直接用时间戳 → 同毫秒请求**覆盖**而非新增 → 计数偏少。member 用 `now .. '-' .. math.random(1000000)` |
| **参数校验完全不生效（越界值也 200）** | `@RequestBody` 参数上**漏了 `@Valid`**。约束注解只是元数据，必须由 `@Valid` 触发。区分：漏 `@Valid` → 静默通过；注解用错类型（如 `@Size` 标 `Integer`）→ 校验器抛 `UnexpectedTypeException` **返 500** |
| **Redis 挂时登录/登出返 500** | `TokenServiceImpl` 的 Redis 读写没包 try/catch。**方向**：签发凭证 fail-closed 503、写黑名单 fail-closed、删 key 可 fail-open |
| 用 `javac` 单独编译本项目报"找不到符号 `log`" | `log` 是 Lombok 生成的，编译时**不能加 `-proc:none`**（会关掉注解处理器）。用 IDEA 构建则无此问题 |
| 静态方法调用报"无法从静态上下文中引用非静态方法" | `IpUtils.getClientIp` 是**实例方法**（和 `JwtUtils` 统一风格，注册成 Bean）。要么用 `ipUtils` 实例调用，要么整体改成静态工具类 —— 别混用 |


---

## 十一、怎么验证改动

每个模块都有一份 Postman 测试文档（在 `md/` 下），按用例走一遍就行。

**测试时的核心前提：HTTP 状态码全是 200，看响应体里的 `code`。**

写新模块的测试时，有几类边界特别容易漏（真实踩过）：

- **空表 / 空结果**——`IN ()` 的语法错误只在表是空的时候出现，开发时表里总有数据，很容易一路测过去都没碰过
- **短输入**——摘要截取、字符串截取的越界，只在输入短的时候暴露
- **并发更新的字段**——更新后要回头确认 `viewCount` 没被重置、`createTime` 没被覆盖
- **权限分支**——**必须用第二个账号**。用同一个账号永远改自己的东西、永远成功，403 那条分支根本触发不到
- **过滤条件是否真的用上了**——评论列表按 `articleId` 过滤，如果只测"有评论的文章"是测不出来的（过滤丢了也返回数据）。**必须查一篇没有评论的文章**，期望 `total = 0` 而不是全表数量

### 缓存怎么验证

本机没装 `redis-cli`，可以在虚拟机上执行，或写临时单测用 `StringRedisTemplate` 打印。

```
① 删掉 detail key 强制 miss
   redis-cli -h 127.0.0.1 -a <密码> DEL learning:article:detail:1

② GET /article/1 → 控制台【应该有】 SELECT
③ 立刻再 GET /article/1 → 控制台【不应该有】 SELECT          ← 命中验证
④ 对比：Redis 的 views 应 +1，而 DB 的 view_count 【不变】    ← 只动缓存不动库
```

**第 ③④ 步是关键判据** —— 两项同时成立，才说明真的走了缓存且没回源。

---

## 十二、文档索引

**全部在 `md/` 目录下**（2026-09-16 从根目录整理进来）：

| 文件 | 内容 |
| --- | --- |
| `用户模块接口文档.md` | 用户增删改查的接口定义、`Result` 与状态码约定 |
| `JWT鉴权拦截器文档.md` | 拦截器职责、放行规则、`preHandle` 各步骤、踩坑清单 |
| `全局异常处理器文档.md` | 五类异常的覆盖范围、`BusinessException` 的设计意图 |
| `Postman接口测试文档.md` | 用户与认证模块的测试用例 |
| `文章模块接口文档.md` | 四张表的建表 SQL 与索引设计、文章模块五个接口、关键实现点 |
| `文章模块Postman测试文档.md` | 文章模块的测试用例，含双账号权限测试 |
| `分类与评论模块接口文档.md` | 分类与评论七个接口的定义、状态码、关键实现点 |
| `分类与评论模块Postman测试文档.md` | 分类与评论的 24 条测试用例，含并发重名、空值缓存、N+1 验证 |
| `Redis缓存设计文档.md` | 缓存 key 设计、浏览量方案取舍、序列化要点、六组验证方法 |
| **`JWT双Token设计文档.md`** | 双 Token 的**方案对比版**（决策前的备选方案） |
| **`JWT双Token实现设计文档.md`** | ⭐ **决策已定 + 类设计 + 12 条踩坑 + 验证清单 —— 做 JWT 相关工作时先读这份** |
| **`逻辑删除设计文档.md`** | 四表逻辑删除、`@TableLogic` 改写规则、**唯一索引冲突的四种方案对比**、连带影响回归清单 |
| **`角色权限设计文档.md`** | role 字段、`@RequireRole` + 授权拦截器、**水平/纵向越权区分**、权限矩阵；第十三节是「实现记录」（4 个测试才暴露的缺陷） |
| **`接口限流设计文档.md`** | 四种限流算法对比、滑动窗口 + Lua、拦截器顺序、**六处 Redis 依赖的降级实测表**；第十四节是「实现记录」 |

本文件 `LearningHANDOFF.md` 和 **`README.md`** 都留在**仓库根目录** —— README 面向访客/面试官（项目介绍 + 快速开始），本文件面向接手继续开发的人（设计决策 + 踩坑 + 待办）。

> **文档约定（2026-09-18 起）**：每份设计文档末尾都有「实现记录」一节，**先写设计 → 实现 → 把实测结果和踩坑回填**。回填后这份文档才够格当面试素材（`角色权限设计文档.md` 第十三节、`接口限流设计文档.md` 第十四节是范本）。
>
> **已知的文档不一致**（2026-09-19 第二轮清掉了前两条）：
> 1. ~~`文章模块接口文档.md` 里的表名写的是 `category` / `comment`（无 `tb_` 前缀）~~ → **已改**：四张表统一 `tb_` 前缀，DDL 已用 `SHOW CREATE TABLE` 与库对齐。
> 2. ~~`逻辑删除设计文档.md` 6.1 第⑤条写"再次删除同一个 id → 仍返 200"~~ → **已改**为 404（404 更对，改文档不改代码）。
> 3. **仍然存在（不打算改）**：「用户模块的修改接口是 `PUT /user`（id 在 body 里）」跟文章模块 `PUT /article/{id}`（id 在路径里）风格不一致。角色接口 `PUT /user/role` 沿用了 body 风格（两个字段一起校验），保持一致即可。

---

## 十三、验证足迹

### 13.1 单元测试（2026-09-19 起）

**51 个用例，JUnit 5 + Mockito**，纯单元测试（不启动 Spring 容器、不连数据库和 Redis）：

| 测试类 | 用例 | 锁住什么 |
| --- | --- | --- |
| `JwtUtilsTest` | 13 | `type` 双向校验；**role 必须用 `Number.class` 取**（用 `String.class` 必抛 `JwtException` —— 把"全站 401"的根因钉成断言）；过期/篡改/换密钥必须验签失败；**同一秒签发两个 token 必须不同**（jti） |
| `CacheKeysTest` | 9 | **uri 必须参与限流 key 拼接**（否则 `/article/1` 与 `/article/2` 共用计数器）；key 格式无双冒号、全部带 `learning:` 前缀 |
| `TokenServiceImplTest` | 17 | **四张降级表**：issue/refresh/isRevoked 的 Redis 失败 → 503；logout 删 key → fail-open、写黑名单 → 503；过期 refreshToken → **401 而非 500** |
| `RateLimitInterceptorTest` | 12 | Redis 挂/脚本返空 → **fail-open 放行且不 NPE**；**userId 为 null 时（登录注册走这条路）不 NPE**；超限返 **429 而非 401**；key 按 userId/IP 分维度 |

**为什么优先测这些**：**降级方向靠接口测试极难复现**（要停 Redis、改端口、重启），而权限与降级逻辑**写错了大部分用例还是绿的** —— 只有把"该放行的"也写成断言才拦得住。

> **跑法**：IDEA 里右键 `src/test/java` → Run Tests。项目**没有 Maven wrapper**（`mvn` 不在 PATH），所以命令行跑需要自己拼 classpath；
> `day43/TestRunner.java.bak` 是为此写的一个 JUnit Platform 运行器（**需要额外加 `junit-platform-launcher` 依赖**，`spring-boot-starter-test` 不传递它，所以在 IDEA 里直接编译会报"程序包不存在"）。

### 13.2 接口实测回归脚本

在 `C:\Users\ASUS\Desktop\java`（**数据库口令已改为读环境变量 `LEARNING_DB_PASS`，不再硬编码**）：

| 脚本 | 覆盖 |
| --- | --- |
| `day42-role-test.ps1` | 角色/归属/提权/404 顺序/refresh 保角色（28 条） |
| `day42-role-crud-test.ps1` | 改角色接口：校验/守卫/作废凭证（21 条） |
| `day42-cat-perm-test.ps1` | 分类写接口管理员专属（8 条） |
| `day42-refresh-fix-test.ps1` | refresh 的 JWT 异常处理（12 条） |
| `day42-ratelimit-verify.ps1` | 限流阈值/ZSET/响应头（端到端） |
| `day42-ratelimit-order.ps1` | 拦截器顺序（403 不消耗额度） |
| `day42-degraded-test.ps1` | 六处 Redis 依赖的降级方向（把 port 改 9999） |
| `day42-final-regression.ps1` | 19 条全功能回归 |
| `day43-pagination-verify.ps1` | 分页参数（拒绝而非截断）+ 三类参数异常 → 400（18 条） |

> **一个值得复用的验证方法**：用**自签 JWT**（从用户作用域取 `JWT_SECRET`，手工拼 header/payload + HMAC-SHA256）来构造"**已过期但签名合法**"或"角色不同"的 token。这样能把"过期分支"和"签名错误分支"彻底分开测，比只发垃圾串有价值得多。

---

## 十四、下一步（2026-09-20 起）

**2026-09-19 已完成**：HTTP/HTTPS 笔记 + 知识库 4 条；改密码接口；分页组件化；三类参数异常处理；51 个单元测试。
所以原来的第 2、3 项已完成，本节重排。

### 项目侧（按建议顺序）

> **2026-09-19 第二轮**：原第 1 项「接口文档一致性收尾」**已完成**；原第 4 项里的「魔法数字提取常量」「`PageResult` 死代码构造器」复核时发现**也已完成**。故重排如下。

1. ~~接口文档一致性收尾~~ —— **已完成**（第二轮，见第八节末尾），顺手补了 `README.md`
2. **Docker 部署**（1 天）—— 简历差异点：Dockerfile + `docker-compose` 起 MySQL/Redis/应用，同时把"`JWT_SECRET` 怎么传进容器、`application-local.yml` 怎么不烤进镜像"讲清楚
3. **继续铺单元测试**（重点补 `UserServiceImpl` 的权限判断、`ArticleServiceImpl` 的缓存降级）
   —— 权限逻辑是**最该有测试的地方**，因为 `!A || !B` 写反时大部分用例还是绿的
4. 低优先（可做可不做）：统一提示语、`JwtProperties` 改用 `@EnableConfigurationProperties`
5. **可选**：给仓库补 **Maven Wrapper（`mvnw`）** —— 现在克隆下来的人既没有 `mvnw` 也没有 `mvn`（`.mvn` 目录是空的），只能靠 IDEA 打开才能构建

### 知识侧

5. **计算机网络收口**：把「从输入 URL 到页面展示」串成一条链路（DNS → TCP → TLS → HTTP → 渲染）
   —— 这一题能把 9.16～9.19 学的全串起来，是面试的综合题
6. **操作系统**（学习路线第二阶段还剩这块：进程/线程、内存管理、IO 模型）
7. 11 月启动简历 + JavaGuide 八股文系统刷题；12 月海投
8. ~~10 月底前决定第二个项目~~ —— **已定：海南麻将联机版（已上线，见辅导任务侧 `HANDOFF.md` 第五节）**。接下来的重点是**把它吃透**（按那四条调用链），Learning 收尾 + 海麻吃透，两个项目就够撑简历



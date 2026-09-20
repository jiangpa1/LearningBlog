-- ══════════════════════════════════════════════════════════════════════
--  Learning 建表脚本
--  MySQL 容器【首次启动、且数据卷为空】时自动执行。
--  来源：2026-09-19 用 SHOW CREATE TABLE 从实际库导出后整理，与代码里的实体一一对应。
--
--  ⚠️ 只在数据卷为空时执行一次。改了这里要重新生效，必须：
--        docker compose down -v && docker compose up -d --build
--     （-v 会删掉数据卷，容器里的数据一起没）
-- ══════════════════════════════════════════════════════════════════════

USE learning;

-- ── 用户表 ────────────────────────────────────────────────────────────
CREATE TABLE `tb_user` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `username`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
  `nickname`    varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `role`        tinyint NOT NULL DEFAULT '0' COMMENT '0=user 1=admin',
  `avatar`      varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '头像地址',
  `password`    varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '密码',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted`     tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=已删',
  PRIMARY KEY (`id` DESC),
  UNIQUE KEY `uk_user_name` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- ── 文章表 ────────────────────────────────────────────────────────────
CREATE TABLE `tb_article` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint       NOT NULL COMMENT '作者用户id',
  `title`       varchar(200) NOT NULL COMMENT '标题',
  `content`     text         NOT NULL COMMENT '正文',
  `summary`     varchar(255) DEFAULT NULL COMMENT '摘要，列表页展示用',
  `category_id` bigint       DEFAULT NULL COMMENT '分类id',
  `view_count`  int          NOT NULL DEFAULT '0' COMMENT '浏览量',
  `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime     DEFAULT NULL COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=已删',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_category_id` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章表';

-- ── 分类表 ────────────────────────────────────────────────────────────
CREATE TABLE `tb_category` (
  `id`      bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`    varchar(50) NOT NULL COMMENT '分类名',
  `deleted` tinyint     NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分类表';

-- ── 评论表 ────────────────────────────────────────────────────────────
-- 索引用复合的 (article_id, create_time)：查询是 WHERE article_id=? ORDER BY create_time DESC，
-- 等值列在前 + 排序列在后，过滤和排序一次扫描完成（EXPLAIN 从 Using filesort 变成 Backward index scan）
CREATE TABLE `tb_comment` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id`  bigint       NOT NULL COMMENT '文章id',
  `user_id`     bigint       NOT NULL COMMENT '评论人id',
  `content`     varchar(500) NOT NULL COMMENT '评论内容',
  `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除 0=正常 1=已删',
  PRIMARY KEY (`id`),
  KEY `idx_article_create` (`article_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论表';

-- ── ⚠️ 第一个管理员要手工指定 ──────────────────────────────────────────
-- 注册接口硬编码 role=0（fail-safe 设计），所以第一个管理员只能手工改。
-- 注册完账号后执行：
--     UPDATE tb_user SET role = 1 WHERE username = '你的账号';
-- 不做这一步，GET /user/list 会返 403（包括你自己）。

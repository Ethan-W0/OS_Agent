-- =============================================
-- 数据库：os_agent
-- 字符集：utf8mb4
-- 排序规则：utf8mb4_0900_ai_ci
-- 引擎：InnoDB
-- =============================================

-- 创建数据库（若不存在）
CREATE DATABASE IF NOT EXISTS `os_agent`
DEFAULT CHARACTER SET utf8mb4
DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 使用数据库
USE `os_agent`;

-- ----------------------------
-- 表1：chat_sessions（聊天会话表）
-- 主键：session_id
-- ----------------------------
DROP TABLE IF EXISTS `chat_sessions`;
CREATE TABLE `chat_sessions` (
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话唯一ID，主键',
    `bound_ssh_connection_id` VARCHAR(64) DEFAULT NULL COMMENT '绑定的SSH连接ID',
    `created_at` DATETIME(6) NOT NULL COMMENT '会话创建时间（微秒级精度）',
    `last_active_at` DATETIME(6) NOT NULL COMMENT '会话最后活跃时间（微秒级精度）',
    `status` VARCHAR(20) DEFAULT NULL COMMENT '会话状态（如active/closed等）',
    PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天会话表';

-- ----------------------------
-- 表2：model_configs（AI模型配置表）
-- 主键：id（自增）
-- ----------------------------
DROP TABLE IF EXISTS `model_configs`;
CREATE TABLE `model_configs` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
    `api_key` VARCHAR(500) NOT NULL COMMENT '模型API密钥',
    `base_url` VARCHAR(500) NOT NULL COMMENT '模型API基础地址',
    `model_name` VARCHAR(100) NOT NULL COMMENT '模型名称（如gpt-3.5-turbo）',
    `timeout_seconds` INT NOT NULL COMMENT '请求超时时间（单位：秒）',
    `updated_at` DATETIME(6) NOT NULL COMMENT '配置更新时间（微秒级精度）',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI模型配置表';

-- ----------------------------
-- 表3：chat_messages（聊天消息/指令表）
-- 主键：id（自增）
-- 外键：session_id 关联 chat_sessions.session_id
-- ----------------------------
DROP TABLE IF EXISTS `chat_messages`;
CREATE TABLE `chat_messages` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
    `command` VARCHAR(1000) DEFAULT NULL COMMENT '用户输入的指令/命令',
    `confirmation_token` VARCHAR(64) DEFAULT NULL COMMENT '二次确认用的token',
    `confirmed` BIT(1) DEFAULT NULL COMMENT '是否已确认执行（0/1）',
    `content` MEDIUMTEXT DEFAULT NULL COMMENT '消息内容/模型返回结果',
    `created_at` DATETIME(6) NOT NULL COMMENT '消息创建时间（微秒级精度）',
    `finished` BIT(1) DEFAULT NULL COMMENT '指令是否已执行完成（0/1）',
    `node_name` VARCHAR(100) DEFAULT NULL COMMENT '执行节点名称',
    `rationale` TEXT DEFAULT NULL COMMENT '模型决策理由',
    `risk_level` VARCHAR(20) DEFAULT NULL COMMENT '风险等级（如low/medium/high）',
    `session_id` VARCHAR(64) NOT NULL COMMENT '关联的会话ID（外键）',
    `suggested_alternative` VARCHAR(500) DEFAULT NULL COMMENT '模型建议的替代方案',
    `type` VARCHAR(30) NOT NULL COMMENT '消息类型（如user/assistant/command等）',
    PRIMARY KEY (`id`),
    -- 外键约束：关联会话表
    CONSTRAINT `fk_chat_messages_session` 
        FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`session_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天消息/指令表';
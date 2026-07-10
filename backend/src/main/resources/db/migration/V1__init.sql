-- V1__init.sql
-- 依設計文件第 5 節。所有主鍵使用 Snowflake ID(BIGINT),不用資料庫自增。
-- 所有表帶 created_at / updated_at(TIMESTAMPTZ,由應用層寫入)。

CREATE TABLE users (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,           -- BCrypt
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- USER / ADMIN
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE events (                               -- 演唱會活動
    id          BIGINT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    venue       VARCHAR(200),
    event_time  TIMESTAMPTZ  NOT NULL,              -- 演出時間
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT / PUBLISHED / CLOSED
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ticket_types (                         -- 票種(搶購標的)
    id              BIGINT PRIMARY KEY,
    event_id        BIGINT        NOT NULL REFERENCES events(id),
    name            VARCHAR(100)  NOT NULL,          -- 例:搖滾區、看台 A
    price           NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    total_stock     INT           NOT NULL CHECK (total_stock >= 0),
    stock_remaining INT           NOT NULL CHECK (stock_remaining >= 0),  -- 防超賣的 DB 底線
    seckill_start   TIMESTAMPTZ   NOT NULL,
    seckill_end     TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE',  -- OFFLINE / ONLINE
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_ticket_types_event ON ticket_types(event_id);

CREATE TABLE orders (
    id              BIGINT PRIMARY KEY,              -- Snowflake,直接作為訂單號
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    event_id        BIGINT        NOT NULL,
    ticket_type_id  BIGINT        NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    status          VARCHAR(30)   NOT NULL,          -- PENDING_PAYMENT / PAID / CANCELLED / EXPIRED
    request_id      VARCHAR(64)   NOT NULL,          -- 冪等鍵(來自 MQ 訊息)
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    paid_at         TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ   NOT NULL,          -- 支付截止時間
    CONSTRAINT uq_orders_request     UNIQUE (request_id),               -- 消費冪等
    CONSTRAINT uq_orders_user_ticket UNIQUE (user_id, ticket_type_id)   -- 每人每票種限購一張
);
CREATE INDEX idx_orders_user ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_status_expire ON orders(status, expire_at)
    WHERE status = 'PENDING_PAYMENT';                -- 兜底掃描超時訂單用(部分索引)

CREATE TABLE stock_logs (                            -- 庫存流水(審計 + 對帳)
    id             BIGINT PRIMARY KEY,
    ticket_type_id BIGINT      NOT NULL,
    order_id       BIGINT      NOT NULL,
    delta          INT         NOT NULL,             -- -1 扣減 / +1 回補
    type           VARCHAR(20) NOT NULL,             -- DEDUCT / REVERT
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_stock_logs_ticket ON stock_logs(ticket_type_id, created_at);

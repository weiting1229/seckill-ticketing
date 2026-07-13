/**
 * 後端 API 契約型別(對照 backend src/main/java/com/seckill/**\/dto)。
 * 所有 Snowflake ID 一律為 string(64-bit 超過 JS safe integer,禁止轉 number);
 * 時間為 ISO-8601 UTC 字串(後端 Instant),前端負責時區顯示。
 */

/** 統一回應格式;code = 0 才成功,非 0 為業務錯誤碼(1xxx 認證、2xxx 活動、3xxx 搶購、4xxx 訂單)。 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

/** 通用分頁回應。page 為 1-based。 */
export interface PageResponse<T> {
  page: number
  size: number
  total: number
  items: T[]
}

// ---------- 認證 ----------

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export interface AccessTokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export interface RegisterResponse {
  userId: string
  username: string
}

export interface MeResponse {
  userId: string
  username: string
  role: 'USER' | 'ADMIN'
}

// ---------- 活動 / 票種(公開) ----------

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED'
export type TicketTypeStatus = 'OFFLINE' | 'ONLINE'

export interface EventSummary {
  id: string
  title: string
  venue: string | null
  eventTime: string
  status: EventStatus
}

/** remaining 讀自 Redis,允許短暫不精確;未預熱或 Redis 無值時為 null。 */
export interface TicketTypeView {
  id: string
  name: string
  price: string | number
  totalStock: number
  remaining: number | null
  seckillStart: string
  seckillEnd: string
  status: TicketTypeStatus
}

/** serverTime 供前端校準搶購倒數,勿用瀏覽器時鐘。 */
export interface EventDetail {
  id: string
  title: string
  description: string | null
  venue: string | null
  eventTime: string
  status: EventStatus
  ticketTypes: TicketTypeView[]
  serverTime: string
}

// ---------- 搶購 ----------

export interface SeckillTokenResponse {
  token: string
  ticketTypeId: string
  expiresInSeconds: number
}

export interface PurchaseResponse {
  requestId: string
  orderId: string
}

export type SeckillResultStatus = 'QUEUING' | 'SUCCESS' | 'FAIL'

export interface SeckillResultResponse {
  status: SeckillResultStatus
  orderId: string | null
  reason: string | null
}

// ---------- 訂單 ----------

export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'CANCELLED' | 'EXPIRED'

export interface OrderResponse {
  id: string
  eventId: string
  ticketTypeId: string
  price: string | number
  status: OrderStatus
  createdAt: string
  paidAt: string | null
  expireAt: string
}

// ---------- admin ----------

export interface EventAdmin {
  id: string
  title: string
  description: string | null
  venue: string | null
  eventTime: string
  status: EventStatus
  createdAt: string
  updatedAt: string
}

export interface TicketTypeAdmin {
  id: string
  eventId: string
  name: string
  price: string | number
  totalStock: number
  stockRemaining: number
  seckillStart: string
  seckillEnd: string
  status: TicketTypeStatus
  createdAt: string
  updatedAt: string
}

export interface WarmupResponse {
  ticketTypeId: string
  status: TicketTypeStatus
  dbStockRemaining: number
  redisStockRemaining: number
  alreadyWarmed: boolean
}

export interface ReconcileResponse {
  ticketTypeId: string
  totalStock: number
  dbStockRemaining: number
  redisStockRemaining: number | null
  validOrderCount: number
  stockLogNetDelta: number
  soldByDb: number
  consistent: boolean
}

// ---------- admin 入參 ----------

export interface EventUpsertRequest {
  title: string
  description: string | null
  venue: string | null
  eventTime: string
  /** 僅更新(PUT)時需要;建立一律 DRAFT,不由入參指定。 */
  status?: EventStatus
}

export interface TicketTypeUpsertRequest {
  /** 僅建立(POST)時需要。以 string 傳遞,後端 Long 可解析,避免精度損失。 */
  eventId?: string
  name: string
  price: string | number
  totalStock: number
  seckillStart: string
  seckillEnd: string
}

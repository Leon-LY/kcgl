/**
 * 客户端幂等键生成（clientReqId / clientUuid 共用，docs/01 7.0）。
 * crypto.randomUUID 优先；非安全上下文（http 局域网调试）回退时间戳+随机。
 */
export function newClientId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `r-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 字典查询（会场 / 仓库 / 分类等下拉数据源）。
 */
import { jsonInit, request } from './core'

// ------------------------------------------------------------------ 字典

export interface Venue {
  id: number
  code: string
  name: string
  enabled: boolean
}

export interface PriceBand {
  id: number
  code: string
  lowerBound: number | null
  upperBound: number | null
  enabled: boolean
}

/** 录入页只用启用会场（停用会场仍可补录属后端语义，前端下拉不给入口）。 */
export function fetchVenues(enabledOnly: boolean): Promise<Venue[]> {
  const query = enabledOnly ? '?enabled=true' : ''
  return request(`/api/venues${query}`, { method: 'GET' })
}

export function fetchPriceBands(): Promise<PriceBand[]> {
  return request('/api/price-bands', { method: 'GET' })
}

// ------------------------------------------------------------------ 字典管理（M2-8b-2）

/** 会场创建（E+，现场自救）/改名（code 是管理号快照来源，锁定不可改）。 */
export function createVenue(payload: { code: string; name: string }): Promise<Venue> {
  return request('/api/venues', jsonInit('POST', payload))
}

export function renameVenue(id: number, name: string): Promise<Venue> {
  return request(`/api/venues/${id}`, jsonInit('PUT', { name }))
}

/** 会场停用/启用（仅管理员；只停用不物理删——历史引用仍在）。 */
export function setVenueStatus(id: number, enabled: boolean): Promise<Venue> {
  return request(`/api/venues/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

/** 档位增/改：左闭右开 [lower, upper)；NULL=无界端；重叠由服务层行锁内校验。 */
export interface PriceBandUpsertPayload {
  code: string
  lowerBound: number | null
  upperBound: number | null
}

export function createPriceBand(payload: PriceBandUpsertPayload): Promise<PriceBand> {
  return request('/api/price-bands', jsonInit('POST', payload))
}

export function updatePriceBand(id: number, payload: PriceBandUpsertPayload): Promise<PriceBand> {
  return request(`/api/price-bands/${id}`, jsonInit('PUT', payload))
}

export function setPriceBandStatus(id: number, enabled: boolean): Promise<PriceBand> {
  return request(`/api/price-bands/${id}/status`, jsonInit('PATCH', { enabled: enabled ? 1 : 0 }))
}

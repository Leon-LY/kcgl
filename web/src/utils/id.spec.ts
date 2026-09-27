import { describe, expect, it } from 'vitest'
import { newClientId } from './id'

describe('newClientId (shared idempotency key for clientReqId/clientUuid)', () => {
  it('produces UUID-shaped values, unique on every call', () => {
    expect(newClientId()).toMatch(/^[0-9a-f-]{36}$/)
    expect(newClientId()).not.toBe(newClientId())
  })
})

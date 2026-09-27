import { describe, expect, it } from 'vitest'
import { newClientId } from './id'

describe('newClientId（clientReqId/clientUuid 共用幂等键）', () => {
  it('UUID 形态且每次不同', () => {
    expect(newClientId()).toMatch(/^[0-9a-f-]{36}$/)
    expect(newClientId()).not.toBe(newClientId())
  })
})

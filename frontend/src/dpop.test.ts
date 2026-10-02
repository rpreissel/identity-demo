import { afterEach, describe, expect, it, vi } from 'vitest'
import { exportPublicJwk, generateDpopKeyPair } from './dpop.ts'

describe('generateDpopKeyPair', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('generates the DPoP key with extractable=false; only the public key is exported (09-dpop D-3)', async () => {
    const generateKey = vi.spyOn(crypto.subtle, 'generateKey')

    const keyPair = await generateDpopKeyPair()

    expect(generateKey).toHaveBeenCalledTimes(1)
    expect(generateKey.mock.calls[0][1]).toBe(false)
    expect(keyPair.privateKey.extractable).toBe(false)
    await expect(crypto.subtle.exportKey('jwk', keyPair.privateKey)).rejects.toThrow()
    await expect(exportPublicJwk(keyPair)).resolves.toMatchObject({ kty: 'EC', crv: 'P-256' })
  })
})

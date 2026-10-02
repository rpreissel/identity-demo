import { afterEach, describe, expect, it, vi } from 'vitest'
import { computeJwkThumbprint } from './dpop.ts'
import { createDeviceProof, getOrCreateDeviceKeyPair } from './deviceKey.ts'

/**
 * jsdom here has no IndexedDB, so persistence itself isn't covered; a minimal empty store stands
 * in where a key has to be generated. The proof JWT is testable with the real WebCrypto.
 */
describe('createDeviceProof', () => {
  async function generateKeyPair(): Promise<CryptoKeyPair> {
    return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
  }

  function decodeSegment(segment: string): Record<string, unknown> {
    const padded = segment.replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(padded))
  }

  it('produces a three-segment JWT with typ=device-proof+jwt and the embedded public jwk', async () => {
    const keyPair = await generateKeyPair()
    const proof = await createDeviceProof(keyPair, 'PATCH', 'https://example.test/tools/abc/auth-device', 'pin')

    const segments = proof.split('.')
    expect(segments).toHaveLength(3)

    const header = decodeSegment(segments[0])
    expect(header.typ).toBe('device-proof+jwt')
    expect(header.alg).toBe('ES256')
    expect(header.jwk).toMatchObject({ kty: 'EC', crv: 'P-256' })
  })

  it('carries htm/htu/userVerification and a fresh jti per call', async () => {
    const keyPair = await generateKeyPair()
    const htu = 'https://example.test/tools/abc/enroll-device'
    const proofA = await createDeviceProof(keyPair, 'PATCH', htu, 'biometric')
    const proofB = await createDeviceProof(keyPair, 'PATCH', htu, 'biometric')

    const payloadA = decodeSegment(proofA.split('.')[1])
    const payloadB = decodeSegment(proofB.split('.')[1])

    expect(payloadA).toMatchObject({ htm: 'PATCH', htu, userVerification: 'biometric' })
    expect(payloadA.jti).not.toBe(payloadB.jti)
  })

  it('embeds a jwk whose thumbprint matches computeJwkThumbprint (same canonicalization as the backend)', async () => {
    const keyPair = await generateKeyPair()
    const proof = await createDeviceProof(keyPair, 'PATCH', 'https://example.test/x', 'pin')
    const header = decodeSegment(proof.split('.')[0])

    const publicJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)
    const expectedThumbprint = await computeJwkThumbprint({
      kty: publicJwk.kty,
      crv: publicJwk.crv,
      x: publicJwk.x,
      y: publicJwk.y,
    })
    const actualThumbprint = await computeJwkThumbprint(header.jwk as JsonWebKey)

    expect(actualThumbprint).toBe(expectedThumbprint)
  })
})

/** An IndexedDB with one empty store: every read misses, every write succeeds. */
function emptyIndexedDb(): IDBFactory {
  const stored = new Map<IDBValidKey, unknown>()
  const db = {
    objectStoreNames: { contains: () => true },
    transaction: () => {
      const tx: { oncomplete?: () => void; objectStore: () => unknown } = {
        objectStore: () => ({
          get: (key: IDBValidKey) => {
            const request: { result?: unknown; onsuccess?: () => void } = {}
            queueMicrotask(() => {
              request.result = stored.get(key)
              request.onsuccess?.()
            })
            return request
          },
          put: (value: unknown, key: IDBValidKey) => {
            stored.set(key, value)
            queueMicrotask(() => tx.oncomplete?.())
          },
        }),
      }
      return tx
    },
  }
  return {
    open: () => {
      const request: { result: unknown; onsuccess?: () => void } = { result: db }
      queueMicrotask(() => request.onsuccess?.())
      return request
    },
  } as unknown as IDBFactory
}

describe('getOrCreateDeviceKeyPair', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('generates the device key with extractable=false, so no script can read the private key (09-dpop D-3)', async () => {
    vi.stubGlobal('indexedDB', emptyIndexedDb())
    const generateKey = vi.spyOn(crypto.subtle, 'generateKey')

    const { keyPair, publicJwk } = await getOrCreateDeviceKeyPair()

    expect(generateKey).toHaveBeenCalledTimes(1)
    expect(generateKey.mock.calls[0][1]).toBe(false)
    expect(keyPair.privateKey.extractable).toBe(false)
    expect(publicJwk).toMatchObject({ kty: 'EC', crv: 'P-256' })
  })
})

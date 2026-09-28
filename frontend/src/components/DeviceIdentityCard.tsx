import { t } from '../texts'
import type { DeviceLinkResponse } from '../types'
import { shorten } from '../format'
import { Disclosure } from './Disclosure'

interface DeviceIdentityCardProps {
  jwkThumbprint?: string
  /** null while loading or not yet asked; DeviceLinkResponse once GET .../device-link answered. */
  deviceLink: DeviceLinkResponse | null
}

/**
 * FE-14: the device identity (JWK thumbprint), always in the demo column's background, even
 * without a channel. Also shows whose account this device is linked to (DeviceAccountLink,
 * docs/02-domaenenmodell.md #1) via `GET .../device-link`. Renewing the key is a demo action
 * next to it (FE-10).
 *
 * Below the DPoP key, one row per further binding (`deviceLink.boundCredentials`), e.g. the
 * `device` method's key or KOBIL's identifier. Each method decides what it discloses; this card
 * only prints it. These are distinct from the channel binding (docs/09-dpop.md).
 */
export function DeviceIdentityCard({ jwkThumbprint, deviceLink }: DeviceIdentityCardProps) {
  const boundCredentials = deviceLink?.boundCredentials ?? []
  return (
    <Disclosure summary={t('Diese App auf diesem Gerät')}>
      <ul className="status-list">
        <li>
          <span className="label">{t('Geräte-Kennung (DPoP)')}</span>
          <span className="value" title={jwkThumbprint}>
            {shorten(jwkThumbprint)}
          </span>
        </li>
        {boundCredentials.map((credential) => (
          <li key={credential.method}>
            <span className="label">{t('An das Gerät gebunden ({methode})', { methode: credential.method })}</span>
            <span className="value" title={credential.reference}>
              {shorten(credential.reference)}
            </span>
          </li>
        ))}
        <li>
          <span className="label">{t('Verknüpft mit')}</span>
          {deviceLink?.linked ? (
            <span className="value">{t('Konto {id}', { id: String(deviceLink.accountId) })}</span>
          ) : (
            <span className="value value-plain">{deviceLink == null ? '…' : t('noch keinem Konto zugeordnet')}</span>
          )}
        </li>
      </ul>
    </Disclosure>
  )
}

import type { ReactNode } from 'react'
import { t } from '../../texts'
import type { Next } from '../../types'
import { explainToolStep } from '../../tools/registry'
import type { ToolRenderContext } from '../../tools/types'
import { UnavailableTools } from '../../components/UnavailableTools'

/** What the demo column says about the screen: why it is there, what happens, who acts. */
export interface AppStepExplanation {
  idleReason?: string
  does: string
  actor: string
  details?: ReactNode
  technical?: string
}

/**
 * The demo column's "why / what / who" for whatever the phone shows right now (StepExplanation):
 * a tool step explains itself (ToolModule.explain), the orchestrator's own screens are explained here.
 */
export function explainAppStep({ toolCtx, next, channelSessionId, pendingPairingCode, deviceLink, uiComponent, availableTools }: {
  toolCtx: ToolRenderContext | undefined
  next: Next | undefined | null
  channelSessionId: string | undefined
  pendingPairingCode: string | undefined
  deviceLink: { linked?: boolean } | null | undefined
  uiComponent: string | null | undefined
  availableTools: string[]
}): AppStepExplanation | undefined {
  if (toolCtx) {
    const explained = explainToolStep(toolCtx.toolId, toolCtx.step)
    return explained && { ...explained, technical: `Tool ${toolCtx.toolId} · ${toolCtx.step}` }
  }
  const technical = next?.type === 'orchestrator' ? `Orchestrator ${next.context} · ${next.step}` : undefined
  if (!channelSessionId) {
    if (pendingPairingCode) {
      return {
        idleReason: t('Die App wurde über den QR-Code eines Browsers geöffnet.'),
        does: t('Bestätigen eröffnet eine Sitzung beim Orchestrator, um die wartende Anmeldung im Browser freizugeben.'),
        actor: t('Sie. Noch läuft keine Sitzung.'),
      }
    }
    return deviceLink?.linked
      ? {
          idleReason: t('Dieses Gerät ist mit einem Konto verbunden - deshalb bietet die App gleich das Anmelden an.'),
          does: t('Anmelden eröffnet eine Sitzung beim Orchestrator, der das Verfahren dieses Geräts vorschlägt.'),
          actor: t('Sie. Noch läuft keine Sitzung.'),
        }
      : {
          idleReason: t('Dieses Gerät gehört noch zu keinem Konto.'),
          does: t('Anmelden sucht ein bestehendes Konto, Registrieren legt ein neues an. Beides eröffnet eine Sitzung beim Orchestrator.'),
          actor: t('Sie. Noch läuft keine Sitzung.'),
        }
  }
  if (uiComponent === 'select-method') {
    return {
      does: t('Der Orchestrator zeigt die Verfahren, die dieser Schritt zulässt - nur solche, die diese App kann und die noch nicht abgelehnt wurden.'),
      actor: t('Sie wählen. Der Orchestrator wartet.'),
      // Only here does "not offered" explain something: why a method is missing from this choice.
      details: <UnavailableTools channel="APP" availableTools={availableTools} />,
      technical,
    }
  }
  if (uiComponent === 'prompt') {
    return {
      does: t('Eine Ja/Nein-Rückfrage des Orchestrators. Ihr Text kommt vom Backend, damit er sich ohne neue App-Version ändern lässt.'),
      actor: t('Sie antworten. Der Orchestrator wartet.'),
      technical,
    }
  }
  if (uiComponent === 'authentication-completed') {
    return {
      idleReason: t('Die Anmeldung ist abgeschlossen, gerade läuft kein Vorgang.'),
      does: t('Die App ruft Daten mit ihrem AccessToken ab. Das Token ist an den Schlüssel der App gebunden (DPoP) und nützt ohne ihn nichts.'),
      actor: t('Sie. Sicherheitsniveau erhöhen, Verfahren ändern oder Abmelden starten je einen neuen Vorgang.'),
      technical,
    }
  }
  return undefined
}

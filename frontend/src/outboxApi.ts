/**
 * The outboxes of the simulated SMS provider and mail server (`/mock-sms`, `/mock-mail`) - foreign
 * systems like the person register, so no generated types and no DPoP. Only in demo mode (ADR-36);
 * outside it the endpoints do not exist.
 */
export interface SentSms {
  sequence: number
  phoneNumber: string
  tan: string
  sentAt: string
}

export interface SentMail {
  sequence: number
  address: string
  code: string
  sentAt: string
}

async function get<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) throw new Error(`GET ${path}: ${response.status}`)
  return (await response.json()) as T
}

export const outboxApi = {
  sms: () => get<SentSms[]>('/mock-sms/outbox'),
  mail: () => get<SentMail[]>('/mock-mail/outbox'),
}

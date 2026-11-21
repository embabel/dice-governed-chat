export interface Claim {
  id: string
  text: string
  pinned: boolean
  sources: string[]
}

export interface Review {
  id: string
  text: string
  sourceId: string
  reason: string
}

export interface Workspace {
  baseline: string
  observedAt: string
  claims: Claim[]
  reviews: Review[]
}

export interface History {
  asOf: string
  claims: Claim[]
}

export interface Source {
  id: string
  text: string
}

export const workspaceUrl = '/api/workspaces/acme'

export const sessions = {
  desk: { label: 'Support desk', authorization: 'Bearer desk-demo' },
  reviewer: { label: 'Reviewer', authorization: 'Bearer reviewer-demo' },
}

export type Session = keyof typeof sessions

export class WorkspaceError extends Error {
  constructor(readonly status: number) {
    super(`Workspace request failed with HTTP ${status}`)
  }
}

export async function request<T>(session: Session, path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(workspaceUrl + path, {
    ...options,
    headers: { Authorization: sessions[session].authorization },
  })
  if (!response.ok) throw new WorkspaceError(response.status)
  return response.json() as Promise<T>
}

const timeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'medium',
})

export function formatTime(value: string) {
  return timeFormat.format(new Date(value))
}

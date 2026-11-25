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
  approvable?: boolean
  target?: Claim | null
}

export interface Workspace {
  baseline: string
  observedAt: string
  claims: Claim[]
  reviews: Review[]
  approvals?: Approval[]
}

export interface Approval {
  id: string
  prior: string
  approvedAt: string
  text: string
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

export function keepLatestWorkspace(current: Workspace | undefined, received: Workspace): Workspace {
  if (!current) return received
  const difference = Date.parse(current.observedAt) - Date.parse(received.observedAt)
  if (difference !== 0) return difference > 0 ? current : received

  // Java Instant can distinguish observations within the same browser millisecond.
  return utcFraction(current.observedAt) > utcFraction(received.observedAt) ? current : received
}

function utcFraction(instant: string): string {
  return (instant.match(/\.(\d{1,9})Z$/)?.[1] ?? '').padEnd(9, '0')
}

const timeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'medium',
})

export function formatTime(value: string) {
  return timeFormat.format(new Date(value))
}

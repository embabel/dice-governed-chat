import { useEffect, useReducer, useRef, useState, type FormEvent } from 'react'
import { Chat } from './chat'
import {
  type Approval, type Claim, formatTime, type History, keepLatestWorkspace, request, type Session, type Source, type Workspace, WorkspaceError,
} from './workspace'

export function DeskWorkspace({ session }: { session: Session }) {
  const [workspace, updateWorkspace] = useReducer(keepLatestWorkspace, undefined)
  const [revision, setRevision] = useState(0)
  const [loadError, setLoadError] = useState('')
  const [approvalError, setApprovalError] = useState('')
  const [approvalMessage, setApprovalMessage] = useState('')
  const [approving, setApproving] = useState<string>()
  const [beforeApproval, setBeforeApproval] = useState<string>()
  const [sourceId, setSourceId] = useState<string>()
  const approvalStatus = useRef<HTMLParagraphElement>(null)
  const restoreApprovalFocus = useRef(false)

  useEffect(() => {
    if (approvalMessage && restoreApprovalFocus.current) approvalStatus.current?.focus()
    restoreApprovalFocus.current = false
  }, [approvalMessage])

  useEffect(() => {
    const controller = new AbortController()
    request<Workspace>(session, '/state', { signal: controller.signal })
      .then((result) => {
        updateWorkspace(result)
        setLoadError('')
      })
      .catch(() => {
        if (!controller.signal.aborted) setLoadError('The workspace could not be loaded.')
      })
    return () => controller.abort()
  }, [session, revision])

  function refresh() {
    setRevision((value) => value + 1)
  }

  async function approve(id: string) {
    if (approving !== undefined) return
    const initiatingControl = document.activeElement
    setApprovalError('')
    setApprovalMessage('')
    setApproving(id)
    const previousObservation = workspace?.observedAt
    try {
      const approved = await request<Workspace>(session, `/reviews/${encodeURIComponent(id)}/approve`, { method: 'POST' })
      restoreApprovalFocus.current = document.activeElement === initiatingControl
      updateWorkspace(approved)
      setBeforeApproval(previousObservation)
      setApprovalMessage('Change approved.')
    } catch (failure) {
      setApprovalError(failure instanceof WorkspaceError && failure.status === 403
        ? 'This session cannot approve changes.'
        : 'The change could not be approved. Check the pending review list.')
      refresh()
    } finally {
      setApproving(undefined)
    }
  }

  return (
    <main className="workspace">
      <div className="workspace-heading">
        <h1>Support desk</h1>
        <p>Ask about current policy, inspect its sources, and review changes to workspace memory.</p>
      </div>

      <nav className="workspace-jumps" aria-label="Workspace sections">
        <a href="#conversation">Conversation</a>
        <a href="#current">Current beliefs</a>
        <a href="#pending">Pending review{workspace && workspace.reviews.length > 0 && <span aria-hidden="true">{workspace.reviews.length}</span>}</a>
        {workspace && <a href="#earlier">Earlier beliefs</a>}
      </nav>

      <div className="workspace-grid">
        <Chat refresh={refresh} onSource={setSourceId} />

        <aside className="card inspection" aria-label="Workspace memory">
          {loadError && (
            <div className="failure">
              <p className="error" role="alert">{loadError}</p>
              <button type="button" className="quiet" onClick={refresh}>Retry</button>
            </div>
          )}
          <section className="inspection-section" id="current" aria-labelledby="current-heading">
            <div className="card-header">
              <h2 id="current-heading">Current beliefs</h2>
            </div>
            {workspace
              ? <ClaimList claims={workspace.claims} onSource={setSourceId} />
              : <p className="empty">{loadError ? 'Workspace unavailable.' : 'Loading workspace…'}</p>}
          </section>

          <section className="inspection-section" id="pending" aria-labelledby="review-heading">
            <div className="card-header">
              <h2 id="review-heading">Pending review</h2>
              {workspace && (
                <span className="count" data-open={workspace.reviews.length > 0} aria-label={`${workspace.reviews.length} pending`}>
                  {workspace.reviews.length}
                </span>
              )}
            </div>
            {workspace?.reviews.length === 0 && <p className="empty">No pending changes.</p>}
            {workspace?.reviews.map((review) => (
              <article className="review" key={review.id}>
                {review.target && (
                  <div className="review-prior">
                    <span>Current</span>
                    <p>{review.target.text}</p>
                  </div>
                )}
                <div className="review-proposal">
                  <span>Proposed</span>
                  <p className="claim-text">{review.text}</p>
                </div>
                <p className="muted">{review.reason}</p>
                <div className="review-actions">
                  <button type="button" className="quiet" onClick={() => setSourceId(review.sourceId)}>View source</button>
                  {session === 'reviewer' && review.approvable !== false && (
                    <button className="secondary" type="button" aria-disabled={approving !== undefined} onClick={() => approve(review.id)}>
                      {approving === review.id ? 'Approving…' : 'Approve'}
                    </button>
                  )}
                </div>
              </article>
            ))}
            <p className="panel-message approval-status" role="status" ref={approvalStatus} tabIndex={-1}>
              {approvalMessage}
            </p>
            {approvalError && <p className="error panel-message" role="alert">{approvalError}</p>}
          </section>

          {workspace && <BeliefHistory session={session} workspace={workspace} beforeApproval={beforeApproval} />}
        </aside>
      </div>
      {sourceId && <SourceDialog session={session} sourceId={sourceId} onClose={() => setSourceId(undefined)} />}
    </main>
  )
}

function ClaimList({ claims, onSource }: { claims: Claim[]; onSource?: (id: string) => void }) {
  if (claims.length === 0) return <p className="empty">No beliefs at this time.</p>

  return (
    <ul className="claims">
      {claims.map((claim) => (
        <li key={claim.id} data-protected={claim.pinned}>
          <p className="claim-text">{claim.text}</p>
          {(claim.pinned || (onSource && claim.sources.length > 0)) && (
            <div className="claim-meta">
              {claim.pinned && <span className="protected">Protected</span>}
              {onSource && claim.sources.map((id, index) => (
                <button key={id} className="quiet" type="button" onClick={() => onSource(id)}>
                  {claim.sources.length > 1 ? `View source ${index + 1}` : 'View source'}
                </button>
              ))}
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

function BeliefHistory({ session, workspace, beforeApproval }: {
  session: Session
  workspace: Workspace
  beforeApproval?: string
}) {
  const [asOf, setAsOf] = useState('')
  const [history, setHistory] = useState<History>()
  const [comparison, setComparison] = useState<History>()
  const [selectedApproval, setSelectedApproval] = useState<string>()
  const [error, setError] = useState('')
  const [reading, setReading] = useState(false)
  const latest = workspace.approvals?.[0]

  async function read(time: string, approval?: Approval) {
    setHistory(undefined)
    setComparison(undefined)
    setSelectedApproval(approval?.id)
    setError('')
    setReading(true)
    const snapshot = (at: string) => request<History>(session, `/history?${new URLSearchParams({ asOf: at })}`)
    try {
      const prior = await snapshot(time)
      const after = approval ? await snapshot(approval.approvedAt) : undefined
      setHistory(prior)
      setComparison(after)
    } catch (failure) {
      setError(failure instanceof WorkspaceError && failure.status === 422
        ? 'No complete baseline covers that time.'
        : 'Earlier beliefs could not be loaded.')
    } finally {
      setReading(false)
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const time = new Date(asOf)
    if (Number.isNaN(time.valueOf())) {
      setError('Choose a valid date and time.')
      return
    }
    void read(time.toISOString())
  }

  return (
    <section className="inspection-section" id="earlier" aria-labelledby="history-heading">
      <div className="card-header"><h2 id="history-heading">Earlier beliefs</h2></div>
      <div className="history-controls">
        <div className="coverage">
          <span className="muted">Coverage starts <time dateTime={workspace.baseline}>{formatTime(workspace.baseline)}</time></span>
          <button className="quiet" type="button" disabled={reading} onClick={() => void read(workspace.baseline)}>At baseline</button>
        </div>
        {latest ? (
          <ol className="approval-list" aria-label="Recent approvals">
            {workspace.approvals?.map((approval, index) => (
              <li key={approval.id}>
                <button type="button" className="approval-point" aria-pressed={selectedApproval === approval.id}
                  disabled={reading} onClick={() => void read(approval.prior, approval)}>
                  <span>{index === 0 ? 'Before approval' : 'Compare approval'}</span>
                  <strong>{approval.text}</strong>
                  <time dateTime={approval.approvedAt}>{formatTime(approval.approvedAt)}</time>
                </button>
              </li>
            ))}
          </ol>
        ) : beforeApproval && (
          <button className="quiet history-shortcut" type="button" disabled={reading} onClick={() => void read(beforeApproval)}>
            Before approval
          </button>
        )}
        <details className="history-custom">
          <summary>Choose a date and time</summary>
          <form className="history-form" onSubmit={submit}>
            <label htmlFor="as-of">As of date and time</label>
            <input id="as-of" type="datetime-local" required value={asOf} onChange={(event) => setAsOf(event.target.value)} />
            <button className="secondary" type="submit" disabled={reading || !asOf}>Read earlier beliefs</button>
          </form>
        </details>
        {error && <p className="error" role="alert">{error}</p>}
        {reading && <p className="muted history-time" role="status">Reading audit history…</p>}
        {history && !comparison && <p className="muted history-time">Beliefs as of <time dateTime={history.asOf}>{formatTime(history.asOf)}</time></p>}
      </div>
      {history && (comparison
        ? <BeliefComparison prior={history} after={comparison} />
        : <ClaimList claims={history.claims} />)}
    </section>
  )
}

function BeliefComparison({ prior, after }: { prior: History; after: History }) {
  const unchanged = (claim: Claim, other: Claim[]) => other.some((value) =>
    value.id === claim.id && value.text === claim.text && value.pinned === claim.pinned)
  return (
    <div className="comparison" aria-label="Approval comparison">
      <div className="compare-earlier">
        <h3>Before</h3>
        <ClaimList claims={prior.claims.filter((claim) => !unchanged(claim, after.claims))} />
      </div>
      <div className="compare-approved">
        <h3>After</h3>
        <ClaimList claims={after.claims.filter((claim) => !unchanged(claim, prior.claims))} />
      </div>
    </div>
  )
}

function SourceDialog({ session, sourceId, onClose }: { session: Session; sourceId: string; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [source, setSource] = useState<Source>()
  const [error, setError] = useState('')
  useEffect(() => {
    dialog.current?.showModal()
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    request<Source>(session, `/sources/${encodeURIComponent(sourceId)}`, { signal: controller.signal })
      .then(setSource)
      .catch(() => {
        if (!controller.signal.aborted) setError('The original message is unavailable.')
      })
    return () => controller.abort()
  }, [session, sourceId])

  return (
    <dialog ref={dialog} onClose={onClose} aria-labelledby="source-heading">
      <div className="dialog-header">
        <h2 id="source-heading">Source message</h2>
        <button className="quiet" type="button" onClick={() => dialog.current?.close()}>Close</button>
      </div>
      {source ? <blockquote>{source.text}</blockquote> : <p role={error ? 'alert' : 'status'}>{error || 'Loading source…'}</p>}
    </dialog>
  )
}

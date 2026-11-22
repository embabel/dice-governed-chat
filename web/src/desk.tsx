import { useEffect, useReducer, useRef, useState, type FormEvent } from 'react'
import { Chat } from './chat'
import {
  type Claim, formatTime, type History, keepLatestWorkspace, request, type Session, type Source, type Workspace, WorkspaceError,
} from './workspace'

export function DeskWorkspace({ session }: { session: Session }) {
  const [workspace, updateWorkspace] = useReducer(keepLatestWorkspace, undefined)
  const [revision, setRevision] = useState(0)
  const [loadError, setLoadError] = useState('')
  const [approvalError, setApprovalError] = useState('')
  const [approving, setApproving] = useState<string>()
  const [beforeApproval, setBeforeApproval] = useState<string>()
  const [sourceId, setSourceId] = useState<string>()

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
    setApprovalError('')
    setApproving(id)
    const previousObservation = workspace?.observedAt
    try {
      updateWorkspace(await request<Workspace>(session, `/reviews/${encodeURIComponent(id)}/approve`, { method: 'POST' }))
      setBeforeApproval(previousObservation)
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
        <p>Review what the assistant remembers about refunds and follow-up.</p>
      </div>

      <nav className="workspace-jumps" aria-label="Workspace sections">
        <a href="#conversation">Conversation</a>
        <a href="#current">Current beliefs</a>
        <a href="#pending">Pending review{workspace && workspace.reviews.length > 0 && <span aria-hidden="true">{workspace.reviews.length}</span>}</a>
        {workspace && <a href="#earlier">Earlier beliefs</a>}
      </nav>

      <div className="workspace-grid">
        <Chat refresh={refresh} />

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
                <p className="claim-text">{review.text}</p>
                <p className="muted">{review.reason}</p>
                <div className="review-actions">
                  <button type="button" className="quiet" onClick={() => setSourceId(review.sourceId)}>View source</button>
                  {session === 'reviewer' && review.approvable !== false && (
                    <button className="secondary" type="button" disabled={approving !== undefined} onClick={() => approve(review.id)}>
                      {approving === review.id ? 'Approving…' : 'Approve'}
                    </button>
                  )}
                </div>
              </article>
            ))}
            {approvalError && <p className="error panel-message" role="alert">{approvalError}</p>}
          </section>

          {workspace && <BeliefHistory session={session} baseline={workspace.baseline} beforeApproval={beforeApproval} />}
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

function BeliefHistory({ session, baseline, beforeApproval }: {
  session: Session
  baseline: string
  beforeApproval?: string
}) {
  const [asOf, setAsOf] = useState('')
  const [history, setHistory] = useState<History>()
  const [error, setError] = useState('')
  const [reading, setReading] = useState(false)

  async function read(time: string) {
    setHistory(undefined)
    setError('')
    setReading(true)
    const query = new URLSearchParams({ asOf: time })
    try {
      setHistory(await request<History>(session, `/history?${query}`))
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
          <span className="muted">Coverage starts <time dateTime={baseline}>{formatTime(baseline)}</time></span>
          <button className="quiet" type="button" disabled={reading} onClick={() => void read(baseline)}>At baseline</button>
        </div>
        {beforeApproval && (
          <button className="quiet history-shortcut" type="button" disabled={reading} onClick={() => void read(beforeApproval)}>
            Before approval
          </button>
        )}
        <form className="history-form" onSubmit={submit}>
          <label htmlFor="as-of">As of date and time</label>
          <input id="as-of" type="datetime-local" required value={asOf} onChange={(event) => setAsOf(event.target.value)} />
          <button className="secondary" type="submit" disabled={reading || !asOf}>Read earlier beliefs</button>
        </form>
        {error && <p className="error" role="alert">{error}</p>}
        {history && <p className="muted history-time">Beliefs as of <time dateTime={history.asOf}>{formatTime(history.asOf)}</time></p>}
      </div>
      {history && <ClaimList claims={history.claims} />}
    </section>
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
      {source ? <blockquote>{source.text}</blockquote> : <p>{error || 'Loading source…'}</p>}
      <p className="source-id">{sourceId}</p>
    </dialog>
  )
}

import { useAgent } from '@copilotkit/react-core/v2/headless'
import { useEffect, useRef, useState, type FormEvent } from 'react'

const examples = [
  'Support leads can approve refunds within 60 days.',
  'Email is our preferred follow-up channel.',
  'Phone is our preferred follow-up channel.',
]

export function Chat({ refresh }: { refresh: () => void }) {
  const { agent, isReady } = useAgent()
  const [draft, setDraft] = useState('')
  const [error, setError] = useState('')
  const busy = useRef(false)
  const transcript = useRef<HTMLDivElement>(null)
  const messages = agent.messages.filter(
    (message) => (message.role === 'assistant' || message.role === 'user') && typeof message.content === 'string',
  )

  useEffect(() => {
    const log = transcript.current
    if (log && messages.length > 0) log.scrollTop = log.scrollHeight
  }, [messages.length])

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const content = draft.trim()
    if (!content || !isReady || busy.current) return

    busy.current = true
    setDraft('')
    setError('')
    agent.addMessage({ id: crypto.randomUUID(), role: 'user', content })
    try {
      await agent.runAgent()
    } catch {
      setError('The message could not be processed. Check the workspace before trying again.')
    } finally {
      busy.current = false
      refresh()
    }
  }

  return (
    <section className="card chat" id="conversation" aria-labelledby="conversation-heading">
      <div className="card-header">
        <h2 id="conversation-heading">Conversation</h2>
        <span className="muted">Acme support</span>
      </div>
      <div className="transcript" ref={transcript} role="log" aria-label="Conversation messages">
        {messages.length === 0 && (
          <div className="chat-empty">
            <p>Report a change to Acme’s refund policy or follow-up preference.</p>
            <div className="examples" aria-label="Example messages">
              {examples.map((example) => <button type="button" key={example} onClick={() => setDraft(example)}>{example}</button>)}
            </div>
          </div>
        )}
        {messages.map((message) => (
          <article key={message.id} className={`message message-${message.role}`}>
            <span>{message.role === 'assistant' ? 'Support assistant' : 'You'}</span>
            <p>{message.content as string}</p>
          </article>
        ))}
      </div>
      <form className="composer" onSubmit={send}>
        <label htmlFor="message">Message support desk</label>
        <textarea id="message" rows={3} maxLength={4096} value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="Write a message…" />
        <div className="composer-footer">
          <span className="muted">Messages become sources for suggested changes.</span>
          <button className="primary" type="submit" disabled={!draft.trim() || !isReady || agent.isRunning}>
            {agent.isRunning ? 'Sending…' : 'Send message'}
          </button>
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </form>
    </section>
  )
}

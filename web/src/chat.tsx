import type { AbstractAgent } from '@ag-ui/client'
import { useAgent } from '@copilotkit/react-core/v2/headless'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Claim } from './workspace'

const examples = [
  'How long do customers have to request a refund?',
  'How should we follow up with customers?',
]

type CitationIndex = Record<string, Claim[]>
const citedMessages = new WeakMap<AbstractAgent, CitationIndex>()

export function Chat({ refresh, onSource }: { refresh: () => void; onSource: (id: string) => void }) {
  const { agent, isReady } = useAgent()
  const [citations, setCitations] = useState<CitationIndex>(() => citedMessages.get(agent) ?? {})
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

  useEffect(() => {
    const subscription = agent.subscribe({
      onCustomEvent: ({ event }) => {
        if (event.name !== 'governed.citations') return
        const value = event.value as { messageId: string; claims: Claim[] }
        const next = { ...citedMessages.get(agent), [value.messageId]: value.claims }
        citedMessages.set(agent, next)
        setCitations(next)
      },
    })
    return () => subscription.unsubscribe()
  }, [agent])

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const submitter = (event.nativeEvent as SubmitEvent).submitter
    const intent = submitter?.getAttribute('value') === 'record' ? 'record' : 'ask'
    const content = draft.trim()
    if (!content || !isReady || busy.current) return

    busy.current = true
    setDraft('')
    setError('')
    agent.addMessage({ id: crypto.randomUUID(), role: 'user', content })
    try {
      await agent.runAgent({ forwardedProps: { intent } })
    } catch {
      setDraft(content)
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
        <span className="protocol-label">AG-UI</span>
      </div>
      <div className="transcript" ref={transcript} role="log" aria-label="Conversation messages">
        {messages.length === 0 && (
          <div className="chat-empty">
            <p>Ask about Acme’s support policy or add a source for review.</p>
            <div className="examples" aria-label="Example messages">
              {examples.map((example) => <button type="button" key={example} onClick={() => setDraft(example)}>{example}</button>)}
            </div>
          </div>
        )}
        {messages.map((message) => (
          <article key={message.id} className={`message message-${message.role}`}>
            <span>{message.role === 'assistant' ? 'Support assistant' : 'You'}</span>
            <p>{message.content as string}</p>
            {citations[message.id]?.length > 0 && (
              <div className="citations" aria-label="Answer evidence">
                {Array.from(new Set(citations[message.id].flatMap((claim) => claim.sources))).map((id, index) => (
                  <button className="source-chip" type="button" key={id} onClick={() => onSource(id)}>
                    Evidence {index + 1}
                  </button>
                ))}
              </div>
            )}
          </article>
        ))}
      </div>
      <form className="composer" onSubmit={send}>
        <label htmlFor="message">Message support desk</label>
        <textarea id="message" rows={3} maxLength={4096} value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="Write a message…" />
        <div className="composer-footer">
          <span className="muted">Ask reads current memory. Recording evidence can open a review.</span>
          <button className="primary" type="submit" value="ask" disabled={!draft.trim() || !isReady || agent.isRunning}>
            Ask
          </button>
          <button type="submit" value="record" disabled={!draft.trim() || !isReady || agent.isRunning}>
            Record as evidence
          </button>
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </form>
    </section>
  )
}

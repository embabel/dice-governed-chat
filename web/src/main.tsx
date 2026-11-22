import { HttpAgent } from '@ag-ui/client'
import { CopilotKitContext, CopilotKitCoreReact, EMPTY_SET } from '@copilotkit/react-core/v2/context'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { DeskWorkspace } from './desk'
import { sessions, type Session, workspaceUrl } from './workspace'
import './style.css'

function App() {
  const [session, setSession] = useState<Session>('desk')
  const [contexts] = useState(() => ({
    desk: copilotKitFor('desk'),
    reviewer: copilotKitFor('reviewer'),
  }))

  return (
    <>
      <header className="topbar">
        <div className="topbar-content">
          <div className="identity">
            <span className="brand">
              <span className="brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
              DICE
            </span>
            <span className="brand-divider" aria-hidden="true" />
            <span className="workspace-name">Acme workspace</span>
          </div>
          <div className="session-switch" role="group" aria-label="Demo session">
            <button type="button" aria-pressed={session === 'desk'} onClick={() => setSession('desk')}>Support desk</button>
            <button type="button" aria-pressed={session === 'reviewer'} onClick={() => setSession('reviewer')}>Reviewer</button>
          </div>
        </div>
      </header>
      <CopilotKitContext.Provider key={session} value={contexts[session]}>
        <DeskWorkspace session={session} />
      </CopilotKitContext.Provider>
    </>
  )
}

function copilotKitFor(session: Session) {
  const agent = new HttpAgent({
    url: `${workspaceUrl}/chat`,
    headers: { Authorization: sessions[session].authorization },
  })
  return {
    copilotkit: new CopilotKitCoreReact({ agents__unsafe_dev_only: { default: agent } }),
    executingToolCallIds: EMPTY_SET,
  }
}

createRoot(document.getElementById('root')!).render(<App />)

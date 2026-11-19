import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { AiCitation } from '@/lib/api/types'

export const AI_LABEL = 'AI-generated — check before use'
export const AI_CAPTION = "Answered from your workspace's documents"

function citationPath({ sourceType, sourceId }: AiCitation): string {
  return sourceType === 'KB_ARTICLE'
    ? `/app/helpdesk/articles/${sourceId}`
    : `/app/hrms/policies/${sourceId}`
}

/** D13: every piece of AI output carries its label, and grounded output names the documents it came from. */
export function AiOutput({
  body,
  extra,
  citations,
  grounded = false,
}: {
  body: string
  /** Structured details shown above the body. */
  extra?: ReactNode
  citations?: AiCitation[]
  /** Adds the "Answered from your workspace's documents" caption and the sources. */
  grounded?: boolean
}) {
  return (
    <div className="space-y-2 rounded-lg border bg-muted/30 p-3 text-sm">
      <p className="text-xs font-medium text-muted-foreground">{AI_LABEL}</p>
      {extra}
      <p className="whitespace-pre-wrap">{body}</p>
      {grounded && (
        <div className="space-y-1 border-t pt-2">
          <p className="text-xs text-muted-foreground">{AI_CAPTION}</p>
          {citations && citations.length > 0 && (
            <ul aria-label="Sources" className="space-y-0.5">
              {citations.map((c) => (
                <li key={`${c.sourceType}:${c.sourceId}`}>
                  <Link to={citationPath(c)} className="underline-offset-4 hover:underline">
                    {c.title}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

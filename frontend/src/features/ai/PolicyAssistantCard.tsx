import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { AiPolicyAnswer } from '@/lib/api/types'
import { useAiAvailable } from './api'
import { AiOutput } from './AiOutput'

const QUESTION_ERROR = 'Ask a question of 3 to 500 characters.'

/** My HR: ask a question and get an answer quoted from the policies the user may read, with the sources. */
export function PolicyAssistantCard() {
  const available = useAiAvailable()
  const api = useApi()
  const [question, setQuestion] = useState('')
  const [fieldError, setFieldError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [answer, setAnswer] = useState<AiPolicyAnswer | null>(null)
  const [busy, setBusy] = useState(false)

  if (!available) return null

  async function ask() {
    const text = question.trim()
    setError(null)
    if (text.length < 3 || text.length > 500) {
      setFieldError(QUESTION_ERROR)
      return
    }
    setFieldError(undefined)
    setBusy(true)
    try {
      setAnswer(await api.post<AiPolicyAnswer>('/ai/hr/policy-questions', { question: text }))
    } catch (e) {
      const invalid =
        e instanceof ApiError ? e.problem.errors?.find((f) => f.field === 'question') : undefined
      if (invalid) setFieldError(invalid.message)
      else setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card role="region" aria-label="Ask about HR policies">
      <CardHeader>
        <CardTitle>Ask about HR policies</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <form
          noValidate
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            void ask()
          }}
        >
          <Field id="ai-question" label="Your question" error={fieldError}>
            <Textarea
              id="ai-question"
              rows={2}
              maxLength={500}
              value={question}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={describedBy('ai-question', fieldError)}
              onChange={(e) => setQuestion(e.target.value)}
            />
          </Field>
          <Button type="submit" size="sm" disabled={busy}>
            Ask
          </Button>
        </form>
        <FormError message={error} />
        {answer && <AiOutput body={answer.answer} citations={answer.citations} grounded />}
      </CardContent>
    </Card>
  )
}

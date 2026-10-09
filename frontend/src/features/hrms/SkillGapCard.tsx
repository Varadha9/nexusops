import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { EmployeeView, SkillGapView } from '@/lib/api/types'
import { invalidateHr } from './invalidation'
import { SKILL_LEVEL_LABELS } from './labels'
import { SkillsDialog } from './SkillsDialog'

/** The skills someone holds, and where they fall short of their job role, with courses that teach the missing ones. */
export function SkillGapCard({ employee }: { employee: EmployeeView }) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [assigning, setAssigning] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const gap = useQuery({
    queryKey: ['hr', 'skill-gap', employee.id],
    queryFn: () => api.get<SkillGapView>(`/hr/employees/${employee.id}/skill-gap`),
  })

  async function assign(courseId: string, title: string) {
    setAssigning(courseId)
    setError(null)
    try {
      await api.post('/hr/training-assignments', { courseId, employeeId: employee.id })
      toast.success('Training assigned.')
      await invalidateHr(queryClient)
    } catch (e) {
      setError(`${title}: ${problemMessage(e)}`)
    } finally {
      setAssigning(null)
    }
  }

  return (
    <Card role="region" aria-label="Skills and gaps">
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle>Skills and gaps</CardTitle>
        {can(PERMISSIONS.hrEmployeeUpdate) && (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit skills
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {employee.skills.length === 0 ? (
          <p className="text-muted-foreground">No skills recorded.</p>
        ) : (
          <ul className="space-y-1">
            {employee.skills.map((s) => (
              <li key={s.skillId} className="flex justify-between gap-3">
                <span>{s.name}</span>
                <span className="text-muted-foreground">{SKILL_LEVEL_LABELS[s.level]}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="space-y-2 border-t pt-3">
          <h3 className="font-medium">Against the job role</h3>
          {error && (
            <p role="alert" className="text-destructive">
              {error}
            </p>
          )}
          {gap.isPending ? (
            <ListSkeleton />
          ) : gap.isError ? (
            <ErrorState error={gap.error} onRetry={() => void gap.refetch()} />
          ) : !gap.data.jobRole ? (
            <p className="text-muted-foreground">No job role, so no required skills.</p>
          ) : gap.data.gaps.length === 0 ? (
            <p className="text-muted-foreground">
              Meets every skill {gap.data.jobRole.title} needs.
            </p>
          ) : (
            <ul className="space-y-3">
              {gap.data.gaps.map((g) => (
                <li key={g.skill.id} className="space-y-1">
                  <p className="font-medium">{g.skill.name}</p>
                  <p className="text-muted-foreground">
                    needs {g.required} · has {g.held ?? 'none'}
                  </p>
                  {g.recommendedCourses.length > 0 && (
                    <ul className="space-y-1">
                      {g.recommendedCourses.map((c) => (
                        <li key={c.id} className="flex items-center justify-between gap-3">
                          <span>{c.title}</span>
                          {can(PERMISSIONS.hrTraining) && (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={assigning === c.id}
                              aria-label={`Assign ${c.title}`}
                              onClick={() => void assign(c.id, c.title)}
                            >
                              Assign
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
      {editing && <SkillsDialog employee={employee} onClose={() => setEditing(false)} />}
    </Card>
  )
}

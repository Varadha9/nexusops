import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { AwayView, HrDashboard } from '@/lib/api/types'
import { formatDate } from '@/lib/format'

function AwayList({ label, people, empty }: { label: string; people: AwayView[]; empty: string }) {
  return (
    <section aria-label={label} className="space-y-3 rounded-lg border p-4">
      <h2 className="font-semibold">{label}</h2>
      {people.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {people.map((a) => (
            <li key={`${a.employee.id}-${a.startDate}`} className="flex flex-wrap gap-x-2">
              <Link
                to={`/app/hrms/employees/${a.employee.id}`}
                className="underline-offset-4 hover:underline"
              >
                {a.employee.name}
              </Link>
              <span className="text-muted-foreground">
                {a.type.name} · {formatDate(a.startDate)} – {formatDate(a.endDate)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** D19: the workforce at a glance. */
export function HrOverviewPage() {
  const api = useApi()
  const dashboard = useQuery({
    queryKey: ['hr', 'dashboard'],
    queryFn: () => api.get<HrDashboard>('/hr/dashboard'),
    refetchOnMount: 'always',
  })
  return (
    <>
      <PageHeader
        title="People overview"
        description="Who works here, who is away, and what needs attention."
      />
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <section aria-label="People" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">People</h2>
            <p className="text-3xl font-semibold tabular-nums">
              <span className="sr-only">Active employees: </span>
              {dashboard.data.activeHeadcount}
            </p>
            <p className="text-sm text-muted-foreground">
              {dashboard.data.upcomingStarts} starting in the next 30 days ·{' '}
              {dashboard.data.joinersLast90Days} joined and {dashboard.data.leaversLast90Days} left
              in the last 90 days
            </p>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {dashboard.data.byDepartment.map((d) => (
                <div key={d.department?.id ?? 'none'} className="col-span-2 grid grid-cols-subgrid">
                  <dt className="text-muted-foreground">{d.department?.name ?? 'No department'}</dt>
                  <dd className="text-right tabular-nums">{d.employees}</dd>
                </div>
              ))}
            </dl>
          </section>
          <section aria-label="Needs attention" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Needs attention</h2>
            <ul className="space-y-1 text-sm">
              <li>
                <Link to="/app/hrms/leave" className="underline-offset-4 hover:underline">
                  Pending leave requests: {dashboard.data.pendingLeaveRequests}
                </Link>
              </li>
              <li>
                <Link
                  to="/app/hrms/training?overdue=true"
                  className="underline-offset-4 hover:underline"
                >
                  Overdue trainings: {dashboard.data.overdueTrainings}
                </Link>
              </li>
              <li>Onboarding in progress: {dashboard.data.onboardingInProgress}</li>
            </ul>
          </section>
          <section aria-label="Most common skill gaps" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Most common skill gaps</h2>
            {dashboard.data.topSkillGaps.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Everyone meets their job role&apos;s skills.
              </p>
            ) : (
              <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
                {dashboard.data.topSkillGaps.map((g) => (
                  <div key={g.skill.id} className="col-span-2 grid grid-cols-subgrid">
                    <dt>{g.skill.name}</dt>
                    <dd className="text-right tabular-nums">
                      {g.employees} {g.employees === 1 ? 'person' : 'people'}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </section>
          <AwayList
            label="Away today"
            people={dashboard.data.awayToday}
            empty="Nobody is away today."
          />
          <AwayList
            label="Away in the next 7 days"
            people={dashboard.data.awayNext7Days}
            empty="Nobody is away in the next 7 days."
          />
        </div>
      )}
    </>
  )
}

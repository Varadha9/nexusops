import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { DepartmentView, EmployeeSummary, JobRoleView, Page } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { EmployeeFormDialog } from './EmployeeFormDialog'
import { EMPLOYMENT_STATUS_LABELS } from './labels'

const SIZE = 20
const VIEWS: Array<{ value: string; label: string }> = [
  { value: '', label: 'Current employees' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'UPCOMING', label: 'Starting soon' },
  { value: 'LEFT', label: 'Left' },
]

export function EmployeesPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const department = params.get('department') ?? ''
  const jobRole = params.get('jobRole') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [hiring, setHiring] = useState(false)

  const employees = useQuery({
    queryKey: ['hr', 'employees', { q, status, department, jobRole, page }],
    queryFn: () =>
      api.get<Page<EmployeeSummary>>(
        `/hr/employees?${toQuery({
          q,
          status,
          departmentId: department,
          jobRoleId: jobRole,
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })
  const departments = useQuery({
    queryKey: ['hr', 'departments', false],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments'),
  })
  const jobRoles = useQuery({
    queryKey: ['hr', 'job-roles', false],
    queryFn: () => api.get<JobRoleView[]>('/hr/job-roles'),
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  const filtered = Boolean(q || status || department || jobRole)

  return (
    <>
      <PageHeader
        title="Employees"
        description="Everyone who works here, and their place in the team."
        actions={
          can(PERMISSIONS.hrEmployeeUpdate) && (
            <Button onClick={() => setHiring(true)}>Hire employee</Button>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="employee-q">Search employees</Label>
            <Input key={q} id="employee-q" name="q" defaultValue={q} placeholder="Name or number" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="employee-status">Show</Label>
          <NativeSelect
            id="employee-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            {VIEWS.map((v) => (
              <option key={v.value} value={v.value}>
                {v.label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="employee-department">Department</Label>
          <NativeSelect
            id="employee-department"
            value={department}
            onChange={(e) => update({ department: e.target.value, page: '' })}
          >
            <option value="">Any department</option>
            {(departments.data ?? []).map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="employee-job-role">Job role</Label>
          <NativeSelect
            id="employee-job-role"
            value={jobRole}
            onChange={(e) => update({ jobRole: e.target.value, page: '' })}
          >
            <option value="">Any job role</option>
            {(jobRoles.data ?? []).map((r) => (
              <option key={r.id} value={r.id}>
                {r.title}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      {employees.isPending ? (
        <ListSkeleton />
      ) : employees.isError ? (
        <ErrorState error={employees.error} onRetry={() => void employees.refetch()} />
      ) : employees.data.items.length === 0 ? (
        <EmptyState
          title={filtered ? 'No employees match these filters.' : 'No employees yet.'}
          description="People you hire appear here."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Number</TableHead>
                  <TableHead>Department</TableHead>
                  <TableHead>Job role</TableHead>
                  <TableHead>Manager</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Start date</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {employees.data.items.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell>
                      <Link
                        to={`/app/hrms/employees/${e.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {e.name}
                      </Link>
                    </TableCell>
                    <TableCell>{e.employeeNumber}</TableCell>
                    <TableCell>{e.department?.name ?? '—'}</TableCell>
                    <TableCell>{e.jobRole?.title ?? '—'}</TableCell>
                    <TableCell>{e.manager?.name ?? '—'}</TableCell>
                    <TableCell>
                      <Badge variant={e.status === 'LEFT' ? 'outline' : 'secondary'}>
                        {EMPLOYMENT_STATUS_LABELS[e.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>{formatDate(e.startDate)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={employees.data.page}
            size={employees.data.size}
            total={employees.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {hiring && (
        <EmployeeFormDialog
          onClose={() => setHiring(false)}
          onSaved={(saved) => {
            setHiring(false)
            navigate(`/app/hrms/employees/${saved.id}`)
          }}
        />
      )}
    </>
  )
}

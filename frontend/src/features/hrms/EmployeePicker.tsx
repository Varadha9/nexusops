import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { EmployeeRef, EmployeeSummary, Page } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Pick an employee: a search box over the server's first 20 matches plus a select. '' = none; the current and picked entries stay listed. */
export function EmployeePicker({
  id,
  label,
  value,
  onChange,
  current,
  error,
  blankLabel = 'None',
}: {
  id: string
  label: string
  value: string
  onChange: (id: string) => void
  current?: EmployeeRef | null
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<EmployeeRef | null>(null)
  const employees = useQuery({
    queryKey: ['hr', 'employee-picker', search.trim()],
    queryFn: () =>
      api.get<Page<EmployeeSummary>>(`/hr/employees?${toQuery({ q: search.trim(), size: 20 })}`),
  })
  const options: EmployeeRef[] = (employees.data?.items ?? []).map((e) => ({
    id: e.id,
    name: e.name,
    employeeNumber: e.employeeNumber,
  }))
  const pinned = [current, picked].filter(
    (e, i, all): e is EmployeeRef => !!e && all.findIndex((o) => o?.id === e.id) === i,
  )
  const searchId = `${id}-search`
  const text = (e: EmployeeRef) => `${e.name} · ${e.employeeNumber}`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label={`Find ${label.toLowerCase()}`}>
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id={id} label={label} error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => {
            const chosen = e.target.value
            setPicked(
              options.find((o) => o.id === chosen) ?? pinned.find((o) => o.id === chosen) ?? null,
            )
            onChange(chosen)
          }}
        >
          <option value="">{blankLabel}</option>
          {pinned.map((e) => (
            <option key={e.id} value={e.id}>
              {text(e)}
            </option>
          ))}
          {options
            .filter((e) => !pinned.some((p) => p.id === e.id))
            .map((e) => (
              <option key={e.id} value={e.id}>
                {text(e)}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}

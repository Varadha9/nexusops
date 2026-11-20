import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { MemberRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** An active team member from GET /hr/members, to link to an employee. '' = no account; the current and picked member stay listed. */
export function MemberSelect({
  id,
  label,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string) => void
  current?: MemberRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<MemberRef | null>(null)
  const members = useQuery({
    queryKey: ['hr', 'members', search.trim()],
    queryFn: () => api.get<MemberRef[]>(`/hr/members?${toQuery({ q: search.trim() })}`),
  })
  const options = members.data ?? []
  const pinned = [current, picked].filter(
    (m, i, all): m is MemberRef => !!m && all.findIndex((o) => o?.id === m.id) === i,
  )
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label="Find a team member">
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
              options.find((m) => m.id === chosen) ?? pinned.find((m) => m.id === chosen) ?? null,
            )
            onChange(chosen)
          }}
        >
          <option value="">No account</option>
          {pinned.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
          {options
            .filter((m) => !pinned.some((p) => p.id === m.id))
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}

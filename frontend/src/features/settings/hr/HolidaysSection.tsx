import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton } from '@/components/states'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { MESSAGES, requiredText } from '@/features/auth/schemas'
import { invalidateHr } from '@/features/hrms/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { HolidayView } from '@/lib/api/types'
import { formatDate, todayIso } from '@/lib/format'

const holidaySchema = z.object({
  date: z.string().min(1, MESSAGES.required),
  name: requiredText(80),
})
type HolidayValues = z.infer<typeof holidaySchema>
const HOLIDAY_FIELDS = ['date', 'name'] as const

/** Settings → HRMS → Public holidays: days that don't count against leave, listed per year. */
export function HolidaysSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const thisYear = Number(todayIso().slice(0, 4))
  const [year, setYear] = useState(thisYear)
  const holidays = useQuery({
    queryKey: ['hr', 'holidays', year],
    queryFn: () => api.get<HolidayView[]>(`/hr/holidays?year=${year}`),
  })
  const [error, setError] = useState<string | null>(null)
  const [removing, setRemoving] = useState(false)
  const form = useForm<HolidayValues>({
    resolver: zodResolver(holidaySchema),
    defaultValues: { date: '', name: '' },
  })
  const dateError = form.formState.errors.date?.message
  const nameError = form.formState.errors.name?.message

  const onAdd = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post('/hr/holidays', { date: values.date, name: values.name.trim() })
      await invalidateHr(queryClient)
      toast.success(`${values.name.trim()} added.`)
      // show the year the holiday went into, so it's visible in the list
      setYear(Number(values.date.slice(0, 4)))
      form.reset()
    } catch (e) {
      if (!applyFieldErrors(e, form.setError, HOLIDAY_FIELDS)) setError(problemMessage(e))
    }
  })

  async function remove(holiday: HolidayView) {
    setRemoving(true)
    setError(null)
    try {
      await api.del(`/hr/holidays/${holiday.id}`)
      await invalidateHr(queryClient)
      toast.success(`${holiday.name} removed.`)
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setRemoving(false)
    }
  }

  return (
    <section aria-label="Public holidays" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">Public holidays</h2>
        <div className="flex items-center gap-2">
          <label htmlFor="holiday-year" className="text-sm">
            Year
          </label>
          <NativeSelect
            id="holiday-year"
            className="w-28"
            value={String(year)}
            onChange={(e) => setYear(Number(e.target.value))}
          >
            {[...new Set([thisYear - 1, thisYear, thisYear + 1, year])]
              .sort((a, b) => a - b)
              .map((y) => (
                <option key={y} value={String(y)}>
                  {y}
                </option>
              ))}
          </NativeSelect>
        </div>
      </div>
      <p className="text-sm text-muted-foreground">
        Public holidays aren&apos;t counted as leave days, along with weekends.
      </p>
      <FormError message={error} />
      {holidays.isPending ? (
        <ListSkeleton />
      ) : holidays.isError ? (
        <ErrorState error={holidays.error} onRetry={() => void holidays.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Date</TableHead>
                <TableHead>Name</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {holidays.data.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} className="text-muted-foreground">
                    No holidays for {year}.
                  </TableCell>
                </TableRow>
              )}
              {holidays.data.map((h) => (
                <TableRow key={h.id}>
                  <TableCell>{formatDate(h.date)}</TableCell>
                  <TableCell>{h.name}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={removing}
                      aria-label={`Remove ${h.name}`}
                      onClick={() => void remove(h)}
                    >
                      Remove
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <form noValidate onSubmit={(e) => void onAdd(e)} className="flex flex-wrap items-start gap-3">
        <Field id="holiday-date" label="Date" error={dateError}>
          <Input
            id="holiday-date"
            type="date"
            aria-invalid={dateError ? true : undefined}
            aria-describedby={describedBy('holiday-date', dateError)}
            {...form.register('date')}
          />
        </Field>
        <Field id="holiday-name" label="Holiday name" error={nameError}>
          <Input
            id="holiday-name"
            maxLength={80}
            aria-invalid={nameError ? true : undefined}
            aria-describedby={describedBy('holiday-name', nameError)}
            {...form.register('name')}
          />
        </Field>
        <Button type="submit" className="mt-6" disabled={form.formState.isSubmitting}>
          Add holiday
        </Button>
      </form>
    </section>
  )
}

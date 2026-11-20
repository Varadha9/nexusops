import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { SKILL_LEVEL_LABELS } from './labels'

/** A skill level 1–5 ('' = not chosen). */
export function SkillLevelSelect({
  id,
  label,
  value,
  onChange,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
}) {
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">Choose…</option>
        {[1, 2, 3, 4, 5].map((n) => (
          <option key={n} value={String(n)}>
            {SKILL_LEVEL_LABELS[n]}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}

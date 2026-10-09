import type { QueryClient } from '@tanstack/react-query'

/** Employees, leave, training and the dashboard depend on each other: any HRMS write refreshes them all. */
export async function invalidateHr(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['hr'] })
}

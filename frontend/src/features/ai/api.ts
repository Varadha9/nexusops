import { useQuery } from '@tanstack/react-query'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { AiStatus } from '@/lib/api/types'

/** The AI assist cards show only when this says AI is on for the workspace and the user may use it. */
export function useAiAvailable(): boolean {
  const api = useApi()
  const canUse = useCan()(PERMISSIONS.aiUse)
  const status = useQuery({
    queryKey: ['ai', 'status'],
    queryFn: () => api.get<AiStatus>('/ai/status'),
    enabled: canUse,
    retry: false,
  })
  return canUse && status.data?.enabled === true
}

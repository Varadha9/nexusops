import type { RouteObject } from 'react-router'
import { HrIndex } from './HrIndex'

/** /app/hrms/* children (Tasks 9–11 add theirs). */
export const hrmsChildren: RouteObject[] = [{ index: true, element: <HrIndex /> }]

import { PageHeader } from '@/components/states'
import { DepartmentsSection } from './hr/DepartmentsSection'
import { HolidaysSection } from './hr/HolidaysSection'
import { JobRolesSection } from './hr/JobRolesSection'
import { LeaveTypesSection } from './hr/LeaveTypesSection'
import { OnboardingTemplatesSection } from './hr/OnboardingTemplatesSection'
import { SkillsSection } from './hr/SkillsSection'

/** Settings → HRMS: the reference data the rest of the HRMS area is built on. */
export function HrSettingsPage() {
  return (
    <>
      <PageHeader
        title="HRMS"
        description="Departments, job roles and the skills they need, leave rules and onboarding checklists."
      />
      <div className="space-y-8">
        <DepartmentsSection />
        <JobRolesSection />
        <SkillsSection />
        <LeaveTypesSection />
        <HolidaysSection />
        <OnboardingTemplatesSection />
      </div>
    </>
  )
}

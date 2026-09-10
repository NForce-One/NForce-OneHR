import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useAuthStore } from './store/authStore';
import LandingPage from './pages/LandingPage';
import RoleGuidePage from './pages/RoleGuidePage';
import Login from './pages/auth/Login';
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage';
import ResetPasswordPage from './pages/auth/ResetPasswordPage';
import ChangePasswordPage from './pages/ChangePasswordPage';
import DashboardPage from './pages/DashboardPage';
import AttendancePage from './pages/AttendancePage';
import SuperAdminRegularizationPage from './pages/SuperAdminRegularizationPage';
import OrgSetupPage from './pages/OrgSetupPage';
import EmployeeMasterPage from './pages/EmployeeMasterPage';
import ExceptionDashboardPage from './pages/ExceptionDashboardPage';
import UserManagementPage from './pages/UserManagementPage';
import ProfilePage from './pages/ProfilePage';
import NotificationsPage from './pages/NotificationsPage';
import DirectoryPage from './pages/DirectoryPage';
import HierarchyPage from './pages/HierarchyPage';
import LeaveHolidaysPage from './pages/LeaveHolidaysPage';
import MyTeamPage from './pages/MyTeamPage';
import ApprovalsPage from './pages/ApprovalsPage';
import MyRequestsPage from './pages/MyRequestsPage';
import AssetsExpensesPage from './pages/AssetsExpensesPage';
import DocumentsPage from './pages/DocumentsPage';
import DocumentsCompliancePage from './pages/DocumentsCompliancePage';
import PoliciesPage from './pages/PoliciesPage';
import OnboardingPage from './pages/OnboardingPage';
import AuditHistoryPage from './pages/AuditHistoryPage';
import AuditSecurityPage from './pages/AuditSecurityPage';
import HelpDeskPage from './pages/HelpDeskPage';
import HelpDeskAdminPage from './pages/HelpDeskAdminPage';
import { toShellRole } from './lib/nav.config';
import { Shell } from './components/Shell';
import { ToastProvider } from './context/ToastContext';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function DocumentsRouter() {
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);
  return role === 'HR Admin' || role === 'Super Admin'
    ? <DocumentsCompliancePage />
    : <DocumentsPage />;
}

// Manager, HR Admin, and Super Admin share the same /audit path with different components —
// only these three roles have an 'audit' nav entry at all (see nav.config.ts). Super Admin gets
// the full Audit & Security view; Manager and HR Admin both get the self-scoped Audit History
// view (the server, not this ternary, enforces that scoping — see AuditLogController).
function AuditRouter() {
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);
  return role === 'Super Admin' ? <AuditSecurityPage /> : <AuditHistoryPage />;
}

// /requests is "My Requests" (Leave/Regularization/Web Clock-In tracker) for Employee/Manager,
// but the Help Desk ticket queue for HR Admin/Super Admin — same nav slot the "HR Service
// Requests" item already reserved (see nav.config.ts). Mirrors DocumentsRouter/AuditRouter.
function RequestsRouter() {
  const user = useAuthStore(s => s.user);
  const role = toShellRole(user?.role);
  return role === 'HR Admin' || role === 'Super Admin'
    ? <HelpDeskAdminPage />
    : <MyRequestsPage />;
}

// Unauthenticated → show landing page. Authenticated → redirect to dashboard.
function LandingOrDashboard() {
  const token = useAuthStore((s) => s.token);
  if (token) return <Navigate to="/dashboard" replace />;
  return <LandingPage />;
}

function RequirePasswordChanged({ children }: { children: React.ReactNode }) {
  const token = useAuthStore((s) => s.token);
  const user  = useAuthStore((s) => s.user);
  if (!token) return <Navigate to="/login" replace />;
  if (user?.mustChangePassword) return <Navigate to="/change-password" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <ToastProvider>
    <BrowserRouter>
      <Routes>
        {/* Public — landing + role guides */}
        <Route path="/" element={<LandingOrDashboard />} />
        <Route path="/role-guide/:role" element={<RoleGuidePage />} />
        <Route path="/login" element={<Login />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        {/* Auth required, password-change gate */}
        <Route
          path="/change-password"
          element={
            <RequireAuth>
              <ChangePasswordPage />
            </RequireAuth>
          }
        />

        {/* Shell layout — all authenticated pages */}
        <Route
          element={
            <RequirePasswordChanged>
              <Shell />
            </RequirePasswordChanged>
          }
        >
          {/* Phase 1 — real pages (per-role Phase 2 items are caught by Shell → ComingInPhase) */}
          <Route path="/dashboard"    element={<DashboardPage />} />
          <Route path="/attendance"   element={<AttendancePage />} />
          <Route path="/attendance/regularization/all" element={<SuperAdminRegularizationPage />} />
          <Route path="/leave"        element={<LeaveHolidaysPage />} />
          <Route path="/my-team"      element={<MyTeamPage />} />
          <Route path="/help"         element={<HelpDeskPage />} />
          <Route path="/approvals"    element={<ApprovalsPage />} />
          <Route path="/requests"     element={<RequestsRouter />} />
          <Route path="/my-requests"  element={<MyRequestsPage />} />
          <Route path="/assets"       element={<AssetsExpensesPage />} />
          <Route path="/employees"    element={<EmployeeMasterPage />} />
          {/* Route path must stay in sync with the 'exceptions' nav.config.ts entry — Shell gates rendering by matching nav item, not this route list */}
          <Route path="/exceptions"   element={<ExceptionDashboardPage />} />
          <Route path="/organization" element={<OrgSetupPage />} />
          <Route path="/access"         element={<UserManagementPage />} />
          <Route path="/masters"        element={<OrgSetupPage />} />
          <Route path="/profile"        element={<ProfilePage />} />
          <Route path="/notifications"  element={<NotificationsPage />} />
          <Route path="/directory"      element={<DirectoryPage />} />
          <Route path="/hierarchy"      element={<HierarchyPage />} />
          <Route path="/documents"      element={<DocumentsRouter />} />
          {/* HR Admin/Super Admin's own "My Documents & Policies" (see nav.config.ts) — distinct
              from /documents, which is their Documents & Compliance admin view. DocumentsPage is
              self-scoped (fetches only the caller's own documents/policies), so it's safe to reuse
              verbatim for these roles too. */}
          <Route path="/my-documents"   element={<DocumentsPage />} />
          <Route path="/policies"       element={<PoliciesPage />} />
          <Route path="/onboarding"     element={<OnboardingPage />} />
          <Route path="/audit"          element={<AuditRouter />} />
        </Route>

        <Route path="*"  element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
    </ToastProvider>
  );
}

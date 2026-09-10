import type { LucideIcon } from 'lucide-react';
import {
  Home, Clock, Calendar, HelpCircle, FileText, Users, GitBranch, Shield, AlertTriangle, Package,
} from 'lucide-react';

export type Role = 'Employee' | 'Manager' | 'HR Admin' | 'Super Admin';

export interface NavItem {
  key: string;
  label: string;
  icon: LucideIcon;
  phase: 1 | 2;
  path: string;
  /**
   * Temporarily gates an otherwise-shipped (phase 1) module behind the same
   * disabled/placeholder treatment as a real Phase 2 item, without changing
   * its `phase` — so consumers that read `phase` for roadmap purposes (e.g.
   * RoleGuidePage's "coming soon" section) aren't misled. Reversing the lock
   * is just deleting this flag / setting it false; nothing else changes.
   */
  locked?: boolean;
}

function item(key: string, label: string, icon: LucideIcon, phase: 1 | 2, locked?: boolean): NavItem {
  return { key, label, icon, phase, path: `/${key}`, locked };
}

/** True if this item should render as the disabled, non-interactive placeholder — either a genuine Phase 2 roadmap item, or a Phase 1 item temporarily locked via `locked: true`. */
export function isNavItemDisabled(item: NavItem): boolean {
  return item.phase > 1 || !!item.locked;
}

/**
 * Phase number to show on the placeholder badge/copy. A temporarily-locked
 * Phase 1 item still displays "P2" so its treatment is visually identical to
 * a real Phase 2 module — this is display-only and never touches `item.phase`.
 */
export function navItemDisplayPhase(item: NavItem): number {
  return item.phase > 1 ? item.phase : 2;
}

export const NAV: Record<Role, NavItem[]> = {
  Employee: [
    item('dashboard', 'Home', Home, 1),
    item('my-team', 'My Team', Users, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('attendance', 'My Attendance', Clock, 1),
    item('leave', 'Leave & Holidays', Calendar, 1),
    item('requests', 'My Requests', HelpCircle, 1),
    item('assets', 'Assets & Expenses', Package, 1),
    item('performance', 'Performance & Growth', GitBranch, 2),
    item('documents', 'My Documents & Policies', FileText, 1),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  Manager: [
    item('dashboard', 'Home', Home, 1),
    item('my-team', 'My Team', Users, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('attendance', 'Team Attendance', Clock, 1),
    item('leave', 'Team Leave & Holidays', Calendar, 1),
    item('requests', 'My Requests', HelpCircle, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('exceptions', 'Exception Dashboard', AlertTriangle, 1),
    item('performance', 'Team Performance', GitBranch, 2),
    item('assets', 'Team Assets & Expenses', Package, 1),
    item('documents', 'My Documents & Policies', FileText, 1),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('audit', 'Audit History', Clock, 1),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  'HR Admin': [
    item('dashboard', 'Home', Home, 1),
    item('my-team', 'My Team', Users, 1),
    item('employees', 'Employee Master', Users, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('onboarding', 'Onboarding', Users, 1),
    item('attendance', 'Attendance Administration', Clock, 1),
    item('leave', 'Leave Administration', Calendar, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('exceptions', 'Exception Dashboard', AlertTriangle, 1),
    item('documents', 'Documents & Compliance', FileText, 1),
    item('policies', 'Policies & Announcements', FileText, 1),
    item('my-documents', 'My Documents & Policies', FileText, 1),
    item('organization', 'Organization Structure', GitBranch, 1),
    item('performance', 'Performance & Engagement', GitBranch, 2),
    item('assets', 'Assets & Expenses', Package, 1),
    item('requests', 'HR Service Requests', HelpCircle, 1),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('audit', 'Audit History', Clock, 1),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
  'Super Admin': [
    item('dashboard', 'Home', Home, 1),
    item('directory', 'People Directory', Users, 1),
    item('hierarchy', 'Org Hierarchy', GitBranch, 1),
    item('access', 'User Management', Shield, 1),
    item('attendance', 'Attendance Administration', Clock, 1),
    item('leave', 'Leave & Holidays', Calendar, 1),
    item('approvals', 'Approval Center', FileText, 1),
    item('assets', 'Assets & Expenses', Package, 1),
    item('workflows', 'Workflow Studio', GitBranch, 2),
    item('masters', 'Organization Masters', FileText, 1),
    item('my-documents', 'My Documents & Policies', FileText, 1),
    item('requests', 'HR Service Requests', HelpCircle, 1),
    item('integrations', 'Integrations', FileText, 2),
    item('audit', 'Audit & Security', Clock, 1),
    item('featurelab', 'Future Feature Lab', HelpCircle, 2),
    item('reports', 'Reports & Analytics', FileText, 2),
    item('help', 'Help & Guidance', HelpCircle, 1),
  ],
};

// ---------------------------------------------------------------------------
// Hierarchical grouping — purely a presentation layer on top of NAV above.
// It never adds/removes access: buildRoleNav() only ever surfaces NavItems
// that already exist in NAV[role]; a group that ends up with no resolved
// children (because the role has none of its items) is dropped entirely.
// ---------------------------------------------------------------------------

/** A leaf in the hierarchy definition is just the NavItem key it points to. */
type HierarchyLeaf = string;

interface HierarchyGroup {
  key: string;
  label: string;
  icon: LucideIcon;
  children: HierarchyEntry[];
}

type HierarchyEntry = HierarchyLeaf | HierarchyGroup;

/**
 * Global module hierarchy. Every role's NAV items are slotted into this same
 * tree (by NavItem key); roles simply see whichever branches contain at
 * least one item they already have access to. This is categorization only —
 * it never grants/hides a module beyond what NAV[role] already decides.
 */
const NAV_HIERARCHY: HierarchyEntry[] = [
  { key: 'home', label: 'Home', icon: Home, children: ['dashboard'] },
  {
    key: 'people', label: 'People', icon: Users, children: [
      'my-team',
      'employees',
      { key: 'people-organization', label: 'Organization', icon: GitBranch, children: ['hierarchy', 'organization', 'directory'] },
    ],
  },
  { key: 'administration', label: 'Administration', icon: Shield, children: ['access', 'masters', 'workflows', 'templates', 'integrations', 'featurelab'] },
  { key: 'time-leave', label: 'Time & Leave', icon: Clock, children: ['attendance', 'leave', 'exceptions'] },
  { key: 'requests-approvals', label: 'Requests & Approvals', icon: FileText, children: ['approvals', 'requests'] },
  { key: 'insights', label: 'Insights', icon: FileText, children: ['audit', 'reports'] },
  { key: 'employee-services', label: 'Employee Services', icon: Package, children: ['onboarding', 'assets', 'performance', 'documents', 'policies', 'my-documents'] },
  'help',
];

export interface ResolvedNavGroup {
  type: 'group';
  key: string;
  label: string;
  icon: LucideIcon;
  children: ResolvedNavNode[];
}

export interface ResolvedNavLeaf {
  type: 'item';
  item: NavItem;
}

export type ResolvedNavNode = ResolvedNavGroup | ResolvedNavLeaf;

function resolveEntry(entry: HierarchyEntry, itemsByKey: Map<string, NavItem>): ResolvedNavNode | null {
  if (typeof entry === 'string') {
    const found = itemsByKey.get(entry);
    return found ? { type: 'item', item: found } : null;
  }
  const children = entry.children
    .map((child) => resolveEntry(child, itemsByKey))
    .filter((n): n is ResolvedNavNode => n !== null);
  if (children.length === 0) return null;
  // A header that ends up fronting exactly one thing (because this role lacks the rest of the
  // branch) isn't a category — it's just that one thing with a redundant extra click in front of
  // it. Promote it directly so every role only ever sees a header when it's actually grouping 2+.
  if (children.length === 1) return children[0];
  return { type: 'group', key: entry.key, label: entry.label, icon: entry.icon, children };
}

/**
 * Resolves the global NAV_HIERARCHY down to exactly what a role can see,
 * reusing the role's existing NAV items verbatim (same key/label/icon/phase/
 * path). Branches with no accessible items are omitted; branches reduced to
 * a single item are flattened to that item (see resolveEntry).
 */
export function buildRoleNav(role: Role): ResolvedNavNode[] {
  const itemsByKey = new Map(NAV[role].map((i) => [i.key, i] as const));
  return NAV_HIERARCHY
    .map((entry) => resolveEntry(entry, itemsByKey))
    .filter((n): n is ResolvedNavNode => n !== null);
}

/**
 * Returns the chain of ancestor group keys leading to `currentKey`, or null
 * if `currentKey` isn't present in this tree. Used to auto-expand a module's
 * parent categories when it becomes the active route.
 */
export function findActiveAncestors(nodes: ResolvedNavNode[], currentKey: string): string[] | null {
  for (const node of nodes) {
    if (node.type === 'item') {
      if (node.item.key === currentKey) return [];
    } else {
      const sub = findActiveAncestors(node.children, currentKey);
      if (sub) return [node.key, ...sub];
    }
  }
  return null;
}

/** Maps the raw DB role on the auth store to the UI role union. */
export function toShellRole(dbRole: string | undefined): Role {
  switch (dbRole) {
    case 'SUPER_ADMIN':  return 'Super Admin';
    case 'HR_ADMIN':     return 'HR Admin';
    case 'MANAGER':      return 'Manager';
    case 'EMPLOYEE':
    case 'DELIVERY':
    case 'FINANCE':
    case 'LEADERSHIP':
    default:             return 'Employee';
  }
}

export const ROLE_LANDING: Record<Role, string> = {
  Employee: '/dashboard',
  Manager: '/dashboard',
  'HR Admin': '/dashboard',
  'Super Admin': '/dashboard',
};

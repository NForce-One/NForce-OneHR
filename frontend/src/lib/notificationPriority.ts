import type { NotificationItem } from '../api/notifications';

export type NotificationPriority = 'HIGH' | 'MEDIUM' | 'LOW';

export type NotificationCategory =
  | 'Leave & Attendance'
  | 'Expense & Assets'
  | 'Documents & Policies'
  | 'Helpdesk'
  | 'Recognition'
  | 'Account & Security'
  | 'General';

const CATEGORY_BY_TYPE: Record<string, NotificationCategory> = {
  // Leave & Attendance
  LEAVE_REQUEST_SUBMITTED:           'Leave & Attendance',
  LEAVE_APPROVED:                    'Leave & Attendance',
  LEAVE_REJECTED:                    'Leave & Attendance',
  ATTENDANCE:                        'Leave & Attendance',
  ATTENDANCE_REQUEST_APPROVED:       'Leave & Attendance',
  ATTENDANCE_REQUEST_REJECTED:       'Leave & Attendance',
  OVERTIME_APPROVED:                 'Leave & Attendance',
  OVERTIME_REJECTED:                 'Leave & Attendance',
  REGULARIZATION_SUBMITTED:         'Leave & Attendance',
  REGULARIZATION_APPROVED:          'Leave & Attendance',
  REGULARIZATION_PARTIALLY_APPROVED:'Leave & Attendance',
  REGULARIZATION_REJECTED:          'Leave & Attendance',
  PENALIZATION_POLICY_CHANGED:      'Leave & Attendance',
  WEB_CLOCK_IN_NOTICE:              'Leave & Attendance',
  // Expense & Assets
  EXPENSE_SUBMITTED:                 'Expense & Assets',
  EXPENSE_MANAGER_APPROVED:          'Expense & Assets',
  EXPENSE_MANAGER_REJECTED:          'Expense & Assets',
  EXPENSE_FINAL_APPROVED:            'Expense & Assets',
  EXPENSE_FINAL_REJECTED:            'Expense & Assets',
  EXPENSE_PAID:                      'Expense & Assets',
  ASSET_REQUEST_SUBMITTED:           'Expense & Assets',
  ASSET_REQUEST_APPROVED:            'Expense & Assets',
  ASSET_REQUEST_REJECTED:            'Expense & Assets',
  ASSET_ASSIGNED:                    'Expense & Assets',
  ASSET_REQUEST_FULFILLED:           'Expense & Assets',
  // Documents & Policies
  DOCUMENT_VERIFIED:                 'Documents & Policies',
  DOCUMENT_REJECTED:                 'Documents & Policies',
  DOCUMENT_REMINDER:                 'Documents & Policies',
  DOCUMENT_EXPIRY:                   'Documents & Policies',
  POLICY_PUBLISHED:                  'Documents & Policies',
  POLICY_REMINDER:                   'Documents & Policies',
  HELP_CONTENT_SUBMITTED:            'Documents & Policies',
  HELP_CONTENT_APPROVED:             'Documents & Policies',
  HELP_CONTENT_REJECTED:             'Documents & Policies',
  // Helpdesk
  HELPDESK_TICKET_CREATED:           'Helpdesk',
  HELPDESK_TICKET_REPLIED:           'Helpdesk',
  HELPDESK_TICKET_STATUS_CHANGED:    'Helpdesk',
  HELPDESK_TICKET_ASSIGNED:          'Helpdesk',
  // Recognition
  KUDOS:                             'Recognition',
  // Account & Security
  ACCOUNT:                           'Account & Security',
  SECURITY:                          'Account & Security',
  ORG_UPDATE:                        'Account & Security',
};

export function getCategory(n: NotificationItem): NotificationCategory {
  return CATEGORY_BY_TYPE[n.type] ?? 'General';
}

export function getPriority(n: NotificationItem): NotificationPriority {
  const p = n.priority as NotificationPriority;
  if (p === 'HIGH' || p === 'MEDIUM' || p === 'LOW') return p;
  return 'MEDIUM';
}

export const ALL_CATEGORIES: NotificationCategory[] = [
  'Leave & Attendance',
  'Expense & Assets',
  'Documents & Policies',
  'Helpdesk',
  'Recognition',
  'Account & Security',
  'General',
];

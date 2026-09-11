import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Bell, CheckCheck, Search, RefreshCw, Inbox, ArrowUpRight, Check,
  ChevronLeft, ChevronRight, AlertCircle,
  CalendarDays, Wallet, FileText, HelpCircle, Star, Shield, UserPlus, KeyRound,
  ClipboardCheck, XCircle, Clock, Loader2, Users,
} from 'lucide-react';
import { notificationsApi, type NotificationItem } from '../api/notifications';
import { useAuthStore } from '../store/authStore';
import {
  getCategory, getPriority,
  ALL_CATEGORIES,
  type NotificationCategory, type NotificationPriority,
} from '../lib/notificationPriority';

// ── Metadata ─────────────────────────────────────────────────────────────────

interface NotifMeta {
  icon: React.ReactNode;
  color: string;
  bg: string;
}

const META_BY_TYPE: Record<string, NotifMeta> = {
  LEAVE_REQUEST_SUBMITTED:           { icon: <CalendarDays size={15} />, color: 'var(--warn)',    bg: 'rgba(224,169,59,.12)' },
  LEAVE_APPROVED:                    { icon: <CalendarDays size={15} />, color: 'var(--ok)',      bg: 'rgba(47,182,124,.12)' },
  LEAVE_REJECTED:                    { icon: <XCircle size={15} />,      color: 'var(--risk)',    bg: 'rgba(228,55,61,.12)'  },
  ATTENDANCE:                        { icon: <ClipboardCheck size={15}/>, color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  ATTENDANCE_REQUEST_APPROVED:       { icon: <ClipboardCheck size={15}/>, color: 'var(--ok)',    bg: 'rgba(47,182,124,.12)' },
  ATTENDANCE_REQUEST_REJECTED:       { icon: <XCircle size={15} />,      color: 'var(--risk)',    bg: 'rgba(228,55,61,.12)'  },
  OVERTIME_APPROVED:                 { icon: <Clock size={15} />,         color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  OVERTIME_REJECTED:                 { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  REGULARIZATION_SUBMITTED:         { icon: <ClipboardCheck size={15}/>, color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  REGULARIZATION_APPROVED:          { icon: <ClipboardCheck size={15}/>, color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  REGULARIZATION_PARTIALLY_APPROVED:{ icon: <ClipboardCheck size={15}/>, color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  REGULARIZATION_REJECTED:          { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  EXPENSE_SUBMITTED:                 { icon: <Wallet size={15} />,        color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  EXPENSE_MANAGER_APPROVED:          { icon: <Wallet size={15} />,        color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  EXPENSE_MANAGER_REJECTED:          { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  EXPENSE_FINAL_APPROVED:            { icon: <Wallet size={15} />,        color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  EXPENSE_FINAL_REJECTED:            { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  EXPENSE_PAID:                      { icon: <Wallet size={15} />,        color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  ASSET_REQUEST_SUBMITTED:           { icon: <Users size={15} />,         color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  ASSET_REQUEST_APPROVED:            { icon: <Users size={15} />,         color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  ASSET_REQUEST_REJECTED:            { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  ASSET_ASSIGNED:                    { icon: <Users size={15} />,         color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  ASSET_REQUEST_FULFILLED:           { icon: <Users size={15} />,         color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  DOCUMENT_VERIFIED:                 { icon: <FileText size={15} />,      color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  DOCUMENT_REJECTED:                 { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  DOCUMENT_REMINDER:                 { icon: <Clock size={15} />,         color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  DOCUMENT_EXPIRY:                   { icon: <AlertCircle size={15} />,   color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  POLICY_PUBLISHED:                  { icon: <FileText size={15} />,      color: 'var(--info)',   bg: 'rgba(76,141,214,.12)' },
  POLICY_REMINDER:                   { icon: <Clock size={15} />,         color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  HELP_CONTENT_SUBMITTED:            { icon: <FileText size={15} />,      color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  HELP_CONTENT_APPROVED:             { icon: <FileText size={15} />,      color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  HELP_CONTENT_REJECTED:             { icon: <XCircle size={15} />,       color: 'var(--risk)',   bg: 'rgba(228,55,61,.12)'  },
  HELPDESK_TICKET_CREATED:           { icon: <HelpCircle size={15} />,    color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  HELPDESK_TICKET_REPLIED:           { icon: <HelpCircle size={15} />,    color: 'var(--info)',   bg: 'rgba(76,141,214,.12)' },
  HELPDESK_TICKET_STATUS_CHANGED:    { icon: <HelpCircle size={15} />,    color: 'var(--info)',   bg: 'rgba(76,141,214,.12)' },
  HELPDESK_TICKET_ASSIGNED:          { icon: <HelpCircle size={15} />,    color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  KUDOS:                             { icon: <Star size={15} />,          color: '#f59e0b',       bg: 'rgba(245,158,11,.12)' },
  ACCOUNT:                           { icon: <UserPlus size={15} />,      color: 'var(--ok)',     bg: 'rgba(47,182,124,.12)' },
  SECURITY:                          { icon: <KeyRound size={15} />,      color: 'var(--warn)',   bg: 'rgba(224,169,59,.12)' },
  ORG_UPDATE:                        { icon: <Shield size={15} />,        color: 'var(--info)',   bg: 'rgba(76,141,214,.12)' },
};

const DEFAULT_META: NotifMeta = {
  icon: <Bell size={15} />, color: 'var(--txt-dim)', bg: 'var(--raised2)',
};

function getMeta(type: string): NotifMeta {
  return META_BY_TYPE[type] ?? DEFAULT_META;
}

const PRIORITY_STYLE: Record<NotificationPriority, { color: string; bg: string; label: string }> = {
  HIGH:   { color: 'var(--risk)',    bg: 'rgba(228,55,61,.12)',  label: 'High'   },
  MEDIUM: { color: 'var(--warn)',    bg: 'rgba(224,169,59,.12)', label: 'Medium' },
  LOW:    { color: 'var(--txt-dim)', bg: 'var(--raised2)',       label: 'Low'    },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function toLocalDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function todayDate(): string { return toLocalDate(new Date().toISOString()); }
function yesterdayDate(): string {
  const d = new Date(); d.setDate(d.getDate() - 1);
  return toLocalDate(d.toISOString());
}

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

function groupByDate(list: NotificationItem[]): { label: string; items: NotificationItem[] }[] {
  const buckets = new Map<string, NotificationItem[]>();
  for (const n of list) {
    const d = toLocalDate(n.createdAt);
    const label = d === todayDate() ? 'Today' : d === yesterdayDate() ? 'Yesterday' : 'Earlier';
    if (!buckets.has(label)) buckets.set(label, []);
    buckets.get(label)!.push(n);
  }
  return ['Today', 'Yesterday', 'Earlier']
    .filter(l => buckets.has(l))
    .map(l => ({ label: l, items: buckets.get(l)! }));
}

function useIsWide(bp = 900): boolean {
  const [wide, setWide] = useState(() => window.matchMedia(`(min-width: ${bp}px)`).matches);
  useEffect(() => {
    const mq = window.matchMedia(`(min-width: ${bp}px)`);
    const h = () => setWide(mq.matches);
    mq.addEventListener('change', h);
    return () => mq.removeEventListener('change', h);
  }, [bp]);
  return wide;
}

// ── Small pieces ──────────────────────────────────────────────────────────────

function Pill({ color, bg, children }: { color: string; bg: string; children: React.ReactNode }) {
  return (
    <span style={{
      fontSize: 10, fontWeight: 600, color, background: bg,
      padding: '2px 7px', borderRadius: 20, lineHeight: 1.6, whiteSpace: 'nowrap', flexShrink: 0,
    }}>
      {children}
    </span>
  );
}

function DateGroupHeader({ label }: { label: string }) {
  return (
    <div style={{
      position: 'sticky', top: 0, zIndex: 2,
      padding: '9px 4px 7px',
      fontSize: 10.5, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase',
      color: 'var(--txt-dim)', background: 'var(--panel)',
    }}>
      {label}
    </div>
  );
}

function SkeletonRow() {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '10px 11px', marginBottom: 4 }}>
      <div className="nf-skeleton" style={{ width: 30, height: 30, borderRadius: 8, flexShrink: 0 }} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6, paddingTop: 1 }}>
        <div className="nf-skeleton" style={{ height: 12, width: '55%', borderRadius: 4 }} />
        <div className="nf-skeleton" style={{ height: 10, width: '80%', borderRadius: 4 }} />
        <div style={{ display: 'flex', gap: 6 }}>
          <div className="nf-skeleton" style={{ height: 14, width: 80, borderRadius: 20 }} />
          <div className="nf-skeleton" style={{ height: 14, width: 46, borderRadius: 20 }} />
        </div>
      </div>
    </div>
  );
}

// ── List row ──────────────────────────────────────────────────────────────────

function NotifRow({
  n, selected, onSelect, onMarkRead,
}: {
  n: NotificationItem;
  selected: boolean;
  onSelect: (n: NotificationItem) => void;
  onMarkRead: (id: number) => void;
}) {
  const meta = getMeta(n.type);
  const cat  = getCategory(n);
  const pri  = getPriority(n);
  const ps   = PRIORITY_STYLE[pri];

  function handleMarkRead(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (!n.read) onMarkRead(n.id);
  }

  return (
    <div
      className="nf-notif-row"
      role="button"
      tabIndex={0}
      aria-current={selected ? 'true' : undefined}
      onClick={() => onSelect(n)}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(n); } }}
      style={{
        position: 'relative',
        display: 'flex', alignItems: 'flex-start', gap: 10,
        padding: '10px 30px 10px 11px',
        marginBottom: 4,
        borderRadius: 8,
        border: '1px solid ' + (selected ? 'var(--line2)' : 'transparent'),
        borderLeft: n.read
          ? (selected ? '1px solid var(--line2)' : '3px solid transparent')
          : '3px solid var(--brand-bright)',
        background: selected ? 'var(--raised2)' : n.read ? 'transparent' : 'rgba(228,55,61,.05)',
        cursor: 'pointer',
        transition: 'background 120ms ease, border-color 120ms ease',
      }}
    >
      <div style={{
        width: 30, height: 30, borderRadius: 8, background: meta.bg, color: meta.color,
        display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
      }}>
        {meta.icon}
      </div>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{
            fontSize: 12.5, fontWeight: n.read ? 500 : 650,
            color: n.read ? 'var(--txt-mut)' : 'var(--txt)',
            flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>
            {n.title}
          </span>
          <span style={{
            fontSize: 10, color: 'var(--txt-dim)', flexShrink: 0,
            fontFamily: 'Inter, sans-serif',
          }}>
            {timeAgo(n.createdAt)}
          </span>
        </div>

        {n.message && (
          <div style={{
            fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 2,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>
            {n.message}
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 5 }}>
          <Pill color={meta.color} bg={meta.bg}>{cat}</Pill>
          <Pill color={ps.color} bg={ps.bg}>{ps.label}</Pill>
          {!n.read && (
            <span aria-hidden="true" style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand-bright)' }} />
          )}
        </div>
      </div>

      {!n.read && (
        <button
          onClick={handleMarkRead}
          aria-label="Mark as read"
          title="Mark as read"
          className="nf-notif-mark-read"
          style={{
            position: 'absolute', top: 8, right: 8,
            width: 22, height: 22, borderRadius: 6,
            border: '1px solid var(--line)', background: 'var(--raised2)',
            color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', padding: 0,
          }}
        >
          <Check size={11} />
        </button>
      )}
    </div>
  );
}

// ── Detail pane ───────────────────────────────────────────────────────────────

function DetailPane({ n, navigate }: { n: NotificationItem | null; navigate: ReturnType<typeof useNavigate> }) {
  if (!n) {
    return (
      <div style={{
        height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        padding: 40, textAlign: 'center',
      }}>
        <div style={{
          width: 56, height: 56, borderRadius: '50%', background: 'var(--raised2)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 14,
        }}>
          <Inbox size={24} style={{ color: 'var(--txt-dim)' }} />
        </div>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 4 }}>No notification selected</div>
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', maxWidth: 240 }}>
          Select a notification from the list to see its full details here.
        </div>
      </div>
    );
  }

  const meta = getMeta(n.type);
  const cat  = getCategory(n);
  const pri  = getPriority(n);
  const ps   = PRIORITY_STYLE[pri];

  return (
    <div style={{ height: '100%', overflowY: 'auto', padding: '26px 30px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{
          width: 42, height: 42, borderRadius: 10, background: meta.bg, color: meta.color,
          display: 'flex', alignItems: 'center', justifyContent: 'center', marginRight: 6,
        }}>
          {meta.icon}
        </div>
        <Pill color={meta.color} bg={meta.bg}>{cat}</Pill>
        <Pill color={ps.color} bg={ps.bg}>{ps.label} priority</Pill>
        <Pill
          color={n.read ? 'var(--txt-dim)' : 'var(--brand-bright)'}
          bg={n.read ? 'var(--raised2)' : 'rgba(228,55,61,.14)'}
        >
          {n.read ? 'Read' : 'Unread'}
        </Pill>
      </div>

      <h2 style={{
        fontFamily: 'Inter, sans-serif',
        fontSize: 19, fontWeight: 700, color: 'var(--txt)',
        margin: '0 0 6px', lineHeight: 1.35, overflowWrap: 'break-word',
      }}>
        {n.title}
      </h2>

      <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 18 }}>
        {new Date(n.createdAt).toLocaleString()}
      </div>

      <div style={{ height: 1, background: 'var(--line)', margin: '0 0 18px' }} />

      <p style={{
        fontSize: 13.5, color: 'var(--txt-mut)', lineHeight: 1.7,
        whiteSpace: 'pre-wrap', overflowWrap: 'break-word', margin: '0 0 24px',
      }}>
        {n.message || 'No additional details provided.'}
      </p>

      {n.linkPath && (
        <button
          onClick={() => navigate(n.linkPath!)}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 6,
            padding: '9px 16px', borderRadius: 8,
            background: 'var(--brand)', color: '#fff', border: 'none',
            fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
          }}
        >
          Open related page <ArrowUpRight size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

type StatusFilter = 'all' | 'unread' | 'read';
type SortOrder = 'newest' | 'oldest';

const SELECT_STYLE: React.CSSProperties = {
  padding: '7px 10px',
  background: 'var(--shell)',
  border: '1px solid var(--line2)',
  borderRadius: 6,
  color: 'var(--txt)',
  fontSize: 12,
  outline: 'none',
  fontFamily: 'Inter, sans-serif',
};

const PAGE_SIZE = 20;

export default function NotificationsPage() {
  const token    = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();
  const isWide   = useIsWide(900);

  const [items, setItems]       = useState<NotificationItem[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [total, setTotal]       = useState(0);
  const [totalPages, setPages]  = useState(0);
  const [page, setPage]         = useState(0);
  const [loading, setLoading]   = useState(true);
  const [fetching, setFetching] = useState(false);
  const [error, setError]       = useState(false);
  const [marking, setMarking]   = useState(false);
  const [markingId, setMarkingId] = useState<number | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const [search, setSearch]           = useState('');
  const [catFilter, setCatFilter]     = useState<NotificationCategory | 'all'>('all');
  const [priFilter, setPriFilter]     = useState<NotificationPriority | 'all'>('all');
  const [statusFilter, setStatus]     = useState<StatusFilter>('all');
  const [sortOrder, setSortOrder]     = useState<SortOrder>('newest');

  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback((p: number, silent = false) => {
    if (!silent) { setLoading(true); setError(false); }
    else setFetching(true);
    notificationsApi.list(token, p, PAGE_SIZE)
      .then(data => {
        setItems(data.content);
        setTotal(data.totalElements);
        setPages(data.totalPages);
        setPage(data.number);
      })
      .catch(() => { if (!silent) setError(true); })
      .finally(() => { setLoading(false); setFetching(false); });
    notificationsApi.unreadCount(token)
      .then(data => setUnreadCount(data.count))
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    load(0);
    intervalRef.current = setInterval(() => load(page, true), 15_000);
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  // Reset interval on page change
  useEffect(() => {
    if (intervalRef.current) clearInterval(intervalRef.current);
    intervalRef.current = setInterval(() => load(page, true), 15_000);
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [page, load]);

  async function handleMarkAll() {
    setMarking(true);
    await notificationsApi.markAllRead(token).catch(() => {});
    setItems(prev => prev.map(n => ({ ...n, read: true })));
    setUnreadCount(0);
    setMarking(false);
  }

  async function handleMarkRead(id: number) {
    setMarkingId(id);
    await notificationsApi.markRead(token, id).catch(() => {});
    setItems(prev => prev.map(n => n.id === id ? { ...n, read: true } : n));
    setUnreadCount(prev => Math.max(0, prev - 1));
    setMarkingId(null);
  }

  function handleSelect(n: NotificationItem) {
    if (!n.read) handleMarkRead(n.id);
    if (isWide) setSelectedId(n.id);
    else if (n.linkPath) navigate(n.linkPath);
  }

  const visible = useMemo(() => {
    let list = items;
    if (statusFilter === 'unread') list = list.filter(n => !n.read);
    if (statusFilter === 'read')   list = list.filter(n => n.read);
    if (catFilter !== 'all')       list = list.filter(n => getCategory(n) === catFilter);
    if (priFilter !== 'all')       list = list.filter(n => getPriority(n) === priFilter);
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      list = list.filter(n => n.title.toLowerCase().includes(q) || (n.message ?? '').toLowerCase().includes(q));
    }
    if (sortOrder === 'oldest') list = [...list].reverse();
    return list;
  }, [items, statusFilter, catFilter, priFilter, search, sortOrder]);

  const groups   = groupByDate(visible);
  const selected = items.find(n => n.id === selectedId) ?? null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column' }} className="nf-notif-page">

      {/* Header */}
      <div style={{ marginBottom: 14, display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <h1 style={{
            fontFamily: 'Inter, sans-serif',
            fontSize: 22, fontWeight: 700, color: 'var(--txt)',
            margin: '0 0 4px', letterSpacing: '-0.01em',
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            Notifications
            {unreadCount > 0 && (
              <span style={{
                padding: '2px 9px',
                background: 'rgba(228,55,61,.15)',
                border: '1px solid rgba(228,55,61,.3)',
                borderRadius: 10, fontSize: 12, fontWeight: 700,
                color: 'var(--brand-bright)',
                fontVariantNumeric: 'tabular-nums',
              }}>
                {unreadCount} unread
              </span>
            )}
          </h1>
          <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', margin: 0 }}>
            {total > 0 ? `${total} total notification${total !== 1 ? 's' : ''}` : 'All caught up'}
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
          <button
            onClick={() => load(page)}
            disabled={fetching || loading}
            aria-label="Refresh"
            title="Refresh"
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              width: 34, height: 34, background: 'transparent',
              border: '1px solid var(--line)', borderRadius: 8,
              cursor: (fetching || loading) ? 'default' : 'pointer', color: 'var(--txt-mut)',
            }}
            onMouseEnter={e => { if (!fetching) { e.currentTarget.style.borderColor = 'var(--brand-bright)'; e.currentTarget.style.color = 'var(--txt)'; } }}
            onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--line)'; e.currentTarget.style.color = 'var(--txt-mut)'; }}
          >
            <RefreshCw size={14} style={(fetching || loading) ? { animation: 'nf-spin 1s linear infinite' } : undefined} />
          </button>

          {unreadCount > 0 && (
            <button
              onClick={handleMarkAll}
              disabled={marking}
              style={{
                display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0,
                padding: '8px 16px', background: 'transparent',
                border: '1px solid var(--line)', borderRadius: 8,
                cursor: 'pointer', color: 'var(--txt-mut)',
                fontSize: 12, fontWeight: 500,
                transition: 'border-color 120ms, color 120ms',
              }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--brand-bright)'; e.currentTarget.style.color = 'var(--txt)'; }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--line)'; e.currentTarget.style.color = 'var(--txt-mut)'; }}
            >
              {marking
                ? <Loader2 size={12} style={{ animation: 'nf-spin 1s linear infinite' }} />
                : <CheckCheck size={13} />}
              Mark all read
            </button>
          )}
        </div>
      </div>

      {/* Toolbar */}
      <div style={{
        display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', marginBottom: 14,
        padding: '10px 12px', background: 'var(--panel)',
        border: '1px solid var(--line)', borderRadius: 10,
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 6, flex: '1 1 180px', maxWidth: 300,
          background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px',
        }}>
          <Search size={13} style={{ color: 'var(--txt-dim)', flexShrink: 0 }} aria-hidden="true" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search notifications…"
            aria-label="Search notifications"
            style={{ flex: 1, minWidth: 0, background: 'transparent', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12, fontFamily: 'Inter, sans-serif' }}
          />
        </div>

        <select value={catFilter} onChange={e => setCatFilter(e.target.value as typeof catFilter)} aria-label="Filter by category" style={SELECT_STYLE}>
          <option value="all">All categories</option>
          {ALL_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
        </select>

        <select value={priFilter} onChange={e => setPriFilter(e.target.value as typeof priFilter)} aria-label="Filter by priority" style={SELECT_STYLE}>
          <option value="all">All priorities</option>
          <option value="HIGH">High</option>
          <option value="MEDIUM">Medium</option>
          <option value="LOW">Low</option>
        </select>

        <select value={statusFilter} onChange={e => setStatus(e.target.value as StatusFilter)} aria-label="Filter by status" style={SELECT_STYLE}>
          <option value="all">All notifications</option>
          <option value="unread">Unread only</option>
          <option value="read">Read only</option>
        </select>

        <select value={sortOrder} onChange={e => setSortOrder(e.target.value as SortOrder)} aria-label="Sort order" style={SELECT_STYLE}>
          <option value="newest">Newest first</option>
          <option value="oldest">Oldest first</option>
        </select>
      </div>

      {/* Body */}
      {loading ? (
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '10px 12px' }}>
          <SkeletonRow /><SkeletonRow /><SkeletonRow /><SkeletonRow />
        </div>
      ) : error ? (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '28px 20px',
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
          color: 'var(--risk)', fontSize: 13,
        }}>
          <AlertCircle size={15} />
          Failed to load notifications. Please refresh.
        </div>
      ) : items.length === 0 ? (
        <div style={{
          padding: '64px 20px', textAlign: 'center',
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
        }}>
          <div style={{
            width: 64, height: 64, borderRadius: '50%',
            background: 'linear-gradient(135deg, rgba(228,55,61,.14), rgba(228,55,61,.03))',
            border: '1px solid var(--line)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 18px',
          }}>
            <Bell size={26} style={{ color: 'var(--brand-bright)' }} />
          </div>
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--txt)', marginBottom: 5 }}>You're all caught up</div>
          <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', maxWidth: 300, margin: '0 auto' }}>
            Leave approvals, expense updates, documents, and account events will appear here.
          </div>
        </div>
      ) : (
        <div className="nf-notif-body" style={{
          display: 'flex', border: '1px solid var(--line)', borderRadius: 10,
          background: 'var(--panel)', overflow: 'hidden', flex: 1, minHeight: 0,
        }}>
          {/* Master pane */}
          <div className="nf-notif-list-pane" style={{ overflowY: 'auto', padding: '8px 8px 8px 10px', minWidth: 0 }}>
            {groups.length === 0 ? (
              <div style={{ padding: '40px 16px', textAlign: 'center', fontSize: 12.5, color: 'var(--txt-dim)' }}>
                No notifications match your filters.
              </div>
            ) : (
              groups.map(group => (
                <div key={group.label}>
                  <DateGroupHeader label={group.label} />
                  {group.items.map(n => (
                    <NotifRow
                      key={n.id}
                      n={n}
                      selected={isWide && n.id === selectedId}
                      onSelect={handleSelect}
                      onMarkRead={id => { if (markingId === null) handleMarkRead(id); }}
                    />
                  ))}
                </div>
              ))
            )}
          </div>

          {/* Detail pane — desktop only */}
          {isWide && (
            <div className="nf-notif-detail-pane" style={{ borderLeft: '1px solid var(--line)', minWidth: 0 }}>
              <DetailPane n={selected} navigate={navigate} />
            </div>
          )}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 12, flexShrink: 0 }}>
          <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
            Page {page + 1} of {totalPages}
          </span>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              onClick={() => { setPage(p => p - 1); setSelectedId(null); load(page - 1); }}
              disabled={page === 0}
              style={{ padding: '6px 10px', background: 'transparent', border: '1px solid var(--line)', borderRadius: 6, cursor: page === 0 ? 'not-allowed' : 'pointer', color: page === 0 ? 'var(--line2)' : 'var(--txt-mut)', display: 'flex', alignItems: 'center' }}
            >
              <ChevronLeft size={14} />
            </button>
            <button
              onClick={() => { setPage(p => p + 1); setSelectedId(null); load(page + 1); }}
              disabled={page >= totalPages - 1}
              style={{ padding: '6px 10px', background: 'transparent', border: '1px solid var(--line)', borderRadius: 6, cursor: page >= totalPages - 1 ? 'not-allowed' : 'pointer', color: page >= totalPages - 1 ? 'var(--line2)' : 'var(--txt-mut)', display: 'flex', alignItems: 'center' }}
            >
              <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      <style>{`
        @keyframes nf-spin { to { transform: rotate(360deg); } }

        .nf-notif-row:hover { background: var(--raised2) !important; }
        .nf-notif-mark-read { opacity: 0; transition: opacity 120ms ease; }
        .nf-notif-row:hover .nf-notif-mark-read,
        .nf-notif-mark-read:focus-visible { opacity: 1; }
        .nf-notif-mark-read:hover { color: var(--txt); border-color: var(--brand-bright) !important; }

        .nf-skeleton {
          background: linear-gradient(90deg, var(--raised2) 25%, var(--line) 50%, var(--raised2) 75%);
          background-size: 200% 100%;
          animation: nf-shimmer 1.5s infinite;
        }
        @keyframes nf-shimmer { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }

        .nf-notif-list-pane { flex: 1 1 auto; }
        .nf-notif-detail-pane { flex: 1 1 auto; }

        @media (min-width: 900px) {
          .nf-notif-page { height: calc(100dvh - 112px); }
          .nf-notif-body { flex: 1 1 auto; }
          .nf-notif-list-pane { flex: 0 0 380px; }
          .nf-notif-detail-pane { flex: 1 1 auto; }
        }
        @media (min-width: 1180px) {
          .nf-notif-list-pane { flex: 0 0 420px; }
        }
      `}</style>
    </div>
  );
}

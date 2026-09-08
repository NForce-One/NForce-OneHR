import { useState } from 'react';
import LeavePage from './LeavePage';
import HolidaysPage from './HolidaysPage';

// Single top-level "Leave & Holidays" module (one NavItem, one route — see nav.config.ts and
// App.tsx) presenting its two existing sub-modules, Leave and Holidays, *sequentially*: exactly
// one renders at a time, in this same content area, switched via the tab bar below — never both
// side by side. Both sub-components are reused completely unmodified (same state, API calls,
// filtering, styling, and behavior as before); this file only adds the tab switcher on top.
// Same minimal underline-tab convention as OnboardingPage's Active/Archived toggle.
type LeaveHolidaysTab = 'leave' | 'holidays';

function tabStyle(active: boolean): React.CSSProperties {
  return {
    padding: '9px 2px',
    color: active ? 'var(--txt)' : 'var(--txt-mut)',
    border: 'none',
    borderBottom: `2px solid ${active ? 'var(--brand)' : 'transparent'}`,
    background: 'none',
    fontSize: 13,
    fontWeight: active ? 600 : 400,
    cursor: 'pointer',
  };
}

export default function LeaveHolidaysPage() {
  const [tab, setTab] = useState<LeaveHolidaysTab>('leave');

  return (
    <div>
      <div style={{ display: 'flex', gap: 18, borderBottom: '1px solid var(--line)', marginBottom: 16 }}>
        <button onClick={() => setTab('leave')} style={tabStyle(tab === 'leave')}>Leave</button>
        <button onClick={() => setTab('holidays')} style={tabStyle(tab === 'holidays')}>Holidays</button>
      </div>
      {tab === 'leave' ? <LeavePage /> : <HolidaysPage />}
    </div>
  );
}

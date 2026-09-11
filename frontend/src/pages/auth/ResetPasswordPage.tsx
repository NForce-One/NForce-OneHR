import { useId, useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Eye, EyeOff } from 'lucide-react';
import { AuthLayout } from './AuthLayout';
import { authApi } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import {
  PASSWORD_CRITERIA as CRITERIA,
  PASSWORD_MIN_LENGTH,
  containsSpace,
  passwordStrengthScore as strengthScore,
  validateNewPassword,
} from '../../utils/passwordPolicy';

const containerVariants = { hidden: {}, show: { transition: { staggerChildren: 0.04 } } };
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.28, ease: [0.23, 1, 0.32, 1] as const } },
};

function PasswordInput({
  id, value, onChange, placeholder, autoComplete, error,
}: {
  id: string; value: string; onChange: (v: string) => void; placeholder: string; autoComplete: string; error?: boolean;
}) {
  const [show, setShow] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <input
        id={id}
        type={show ? 'text' : 'password'}
        autoComplete={autoComplete}
        placeholder={placeholder}
        value={value}
        onChange={e => onChange(e.target.value)}
        style={{
          width: '100%',
          background: 'var(--shell)',
          border: `1px solid ${error ? 'rgba(228,55,61,.5)' : 'var(--line2)'}`,
          borderRadius: 6,
          padding: '10px 44px 10px 12px',
          color: 'var(--txt)',
          fontSize: 14,
          outline: 'none',
          fontFamily: 'Inter, sans-serif',
          boxSizing: 'border-box',
        }}
      />
      <button
        type="button"
        aria-label={show ? 'Hide password' : 'Show password'}
        onClick={() => setShow(v => !v)}
        style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', padding: 4, borderRadius: 4 }}
      >
        {show ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
      </button>
    </div>
  );
}

// Reached from the "Reset your password" link in the forgot-password email (see
// EmailService#buildResetHtml), which carries the email as a query param so this screen never
// has to ask for it again — the user goes straight from Temporary Password to New Password to
// Confirm Password, addressing the "asked for email twice" complaint in the forgot-password flow.
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const email = params.get('email');
  const navigate = useNavigate();
  const setAuth = useAuthStore(s => s.setAuth);
  const reduced = useReducedMotion();
  const tempId = useId();
  const newId = useId();
  const confirmId = useId();

  const [tempPassword,    setTempPassword]    = useState('');
  const [newPassword,     setNewPassword]     = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // No email on the link (opened directly, or an old/malformed link) — nothing useful to do
  // here, so send them back to request a fresh reset rather than showing a broken form.
  if (!email) return <Navigate to="/forgot-password" replace />;

  const score = strengthScore(newPassword);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!tempPassword) { setError('Enter the temporary password from your email.'); return; }
    const newPasswordError = validateNewPassword(newPassword);
    if (newPasswordError) { setError(newPasswordError); return; }
    if (!confirmPassword) { setError('Please confirm your new password.'); return; }
    if (containsSpace(confirmPassword)) { setError('Password cannot contain spaces.'); return; }
    if (newPassword !== confirmPassword) { setError('Passwords do not match.'); return; }

    setSubmitting(true);
    try {
      const login = await authApi.login(email!, tempPassword);
      if (!login.mustChangePassword) {
        // Already changed (e.g. a stale link reused after the flow was already completed) —
        // the temp password still worked, so just sign them in rather than blocking here.
        setAuth(login.token, { email: login.email, mustChangePassword: false, role: login.role, fullName: login.fullName });
        navigate('/dashboard', { replace: true });
        return;
      }
      const changed = await authApi.changePassword(undefined, newPassword, confirmPassword, login.token);
      setAuth(changed.token, { email: login.email, mustChangePassword: false, role: login.role, fullName: login.fullName });
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  const strengthColors = ['#2a2a2a', '#ef4444', '#f59e0b', '#22c55e', '#22c55e'];
  const strengthLabels = ['', 'Weak', 'Fair', 'Good', 'Strong'];

  return (
    <AuthLayout
      leftHeadline="Set your new password"
      leftSubtext="Enter the temporary password from your email, then choose a new password to finish resetting your account."
    >
      <motion.div variants={reduced ? undefined : containerVariants} initial={reduced ? undefined : 'hidden'} animate={reduced ? undefined : 'show'}>
        <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 24 }}>
          <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 26, fontWeight: 700, letterSpacing: '-0.01em', color: 'var(--txt)', marginBottom: 6 }}>
            Reset password
          </h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Resetting password for <strong style={{ color: 'var(--txt)' }}>{email}</strong>
          </p>
        </motion.div>

        <form onSubmit={handleSubmit} noValidate>
          {error && (
            <motion.div
              initial={reduced ? undefined : { opacity: 0, y: -6 }}
              animate={reduced ? undefined : { opacity: 1, y: 0 }}
              transition={{ duration: 0.2 }}
              role="alert"
              style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 10, padding: '16px 18px', marginBottom: 20 }}
            >
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.6, margin: 0 }}>{error}</p>
            </motion.div>
          )}

          <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 16 }}>
            <label htmlFor={tempId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Temporary password</label>
            <PasswordInput id={tempId} value={tempPassword} onChange={setTempPassword} placeholder="From your email" autoComplete="current-password" />
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 16 }}>
            <label htmlFor={newId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>New password</label>
            <PasswordInput id={newId} value={newPassword} onChange={setNewPassword} placeholder="••••••••" autoComplete="new-password" />
            {newPassword && (
              <div style={{ marginTop: 8 }}>
                <div style={{ display: 'flex', gap: 4, marginBottom: 6 }}>
                  {[1, 2, 3, 4].map(level => (
                    <div key={level} style={{ height: 4, flex: 1, borderRadius: 2, background: level <= score ? strengthColors[score] : 'var(--line2)' }} />
                  ))}
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px' }}>
                  {[{ label: `At least ${PASSWORD_MIN_LENGTH} characters`, test: (p: string) => p.length >= PASSWORD_MIN_LENGTH }, ...CRITERIA].map(c => (
                    <span key={c.label} style={{ fontSize: 11, color: c.test(newPassword) ? '#22c55e' : 'var(--txt-dim)' }}>{c.label}</span>
                  ))}
                </div>
                {score > 0 && <span style={{ fontSize: 11, fontWeight: 600, color: strengthColors[score] }}>{strengthLabels[score]}</span>}
              </div>
            )}
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 18 }}>
            <label htmlFor={confirmId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Confirm new password</label>
            <PasswordInput id={confirmId} value={confirmPassword} onChange={setConfirmPassword} placeholder="••••••••" autoComplete="new-password" />
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants}>
            <button
              type="submit"
              disabled={submitting}
              style={{
                width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '11px 16px',
                background: 'var(--brand)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontWeight: 600,
                cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.75 : 1,
              }}
            >
              {submitting ? 'Setting password…' : 'Set password & continue'}
            </button>
          </motion.div>
        </form>
      </motion.div>
    </AuthLayout>
  );
}

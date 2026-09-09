package com.nforce.onehr.service;

import com.nforce.onehr.dto.*;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.exception.AccountLockedException;
import com.nforce.onehr.exception.AccountNotFoundException;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.JwtTokenProvider;
import com.nforce.onehr.util.RoleUtils;
import com.nforce.onehr.validation.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final Clock clock;

    private static final String GENERIC_CRED_ERROR = "Invalid credentials";
    private static final int MAX_FAILED_ATTEMPTS = 7;
    private static final Duration LOCK_DURATION = Duration.ofHours(4);

    // Attempts 1-6 return the generic "Invalid credentials" 401; noRollbackFor is required
    // because both branches persist failed-attempt/lockout state on the user row via
    // userRepository.save(), and Spring would otherwise roll that back along with the rest of
    // the transaction when the method exits via one of these exceptions.
    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class})
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim()).orElse(null);

        if (user != null && isCurrentlyLocked(user)) {
            throw new AccountLockedException(user.getEmail(), user.getLockedUntil());
        }

        // Always run bcrypt even when user not found — prevents timing-based user enumeration
        String candidateHash = user != null
                ? user.getPasswordHash()
                : "$2b$10$LxaD8jQMsa8UGDvF4uiIwOvGJFcss7qLGGdChmxhvfiL7f9ylIxje";
        boolean credentialsValid = passwordEncoder.matches(request.getPassword(), candidateHash);

        if (user == null || !credentialsValid) {
            if (user != null) {
                registerFailedAttempt(user);
            }
            throw new BadCredentialsException(GENERIC_CRED_ERROR);
        }

        if (!user.isActive()) {
            auditService.log(user.getId(), "LOGIN_BLOCKED", user.getId());
            throw new DisabledException("Account has been deactivated");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getEmail(), user.isMustChangePassword(), user.getTokenVersion());
        auditService.log(user.getId(), "LOGIN_SUCCESS", user.getId());

        String roleCode = RoleUtils.primaryRoleCode(user.getRoles(), "EMPLOYEE");
        String fullName = employeeRepository.findById(user.getId())
                .map(com.nforce.onehr.entity.Employee::getFullName)
                .orElse(user.getEmail());

        return LoginResponse.builder()
                .token(token)
                .mustChangePassword(user.isMustChangePassword())
                .email(user.getEmail())
                .role(roleCode)
                .fullName(fullName)
                .build();
    }

    // Returns whether the user is still within an active lock window. A lock whose expiry has
    // already passed is cleared here (and the attempt counter reset) so the very next login
    // attempt after 4 hours is processed normally, per the auto-expiry requirement.
    private boolean isCurrentlyLocked(User user) {
        Instant lockedUntil = user.getLockedUntil();
        if (lockedUntil == null) {
            return false;
        }
        if (clock.instant().isBefore(lockedUntil)) {
            return true;
        }
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        return false;
    }

    private void registerFailedAttempt(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            Instant lockedUntil = clock.instant().plus(LOCK_DURATION);
            user.setLockedUntil(lockedUntil);
            userRepository.save(user);
            auditService.log(user.getId(), "LOGIN_LOCKED", user.getId());
            throw new AccountLockedException(user.getEmail(), lockedUntil);
        }

        userRepository.save(user);
        auditService.log(user.getId(), "LOGIN_FAILED", user.getId());
    }

    @Transactional
    public ChangePasswordResponse changePassword(ChangePasswordRequest request,
                                                  String currentUserEmail) {
        User user = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (!user.isActive()) {
            throw new DisabledException("Account has been deactivated");
        }

        // Forced flow (user just logged in with a temp/reset password and must change it
        // before continuing): the frontend omits currentPassword entirely, since the user
        // already proved they know it by using it to authenticate and obtain this request's
        // JWT. Voluntary flow (already-authenticated user changing their password from My
        // Profile): currentPassword is required and verified as before.
        if (!user.isMustChangePassword()) {
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new IllegalArgumentException("Current password is required");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                auditService.log(user.getId(), "PASSWORD_CHANGE_FAILED", user.getId());
                throw new BadCredentialsException("Current password is incorrect");
            }
        }

        // newPassword is already checked for spaces by @ValidPassword on ChangePasswordRequest,
        // but confirmPassword carries no such annotation (it only needs to equal newPassword) —
        // check it explicitly so a space-only difference is reported as a space error rather
        // than a generic mismatch.
        if (PasswordPolicy.containsSpace(request.getConfirmPassword())) {
            throw new IllegalArgumentException(PasswordPolicy.SPACE_MESSAGE);
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from current password");
        }

        // Minimum length, max length, whitespace, and character-class complexity are already
        // enforced by @Valid on ChangePasswordRequest (see @Size + @ValidPassword on
        // newPassword) before this method is ever invoked, so re-checking here would just be a
        // second, driftable copy of the same policy. See PasswordPolicy for the single source
        // of truth these annotations share.

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        // Invalidates every JWT already issued to this user (see JwtAuthenticationFilter) —
        // their very next API call fails auth. The token minted just below carries this new
        // version, so the session performing the change stays logged in.
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setTokenVersionReason("PASSWORD_CHANGED");
        userRepository.save(user);

        auditService.log(user.getId(), "PASSWORD_CHANGED", user.getId());

        String newToken = jwtTokenProvider.generateToken(user.getEmail(), false, user.getTokenVersion());

        return ChangePasswordResponse.builder()
                .token(newToken)
                .message("Password changed successfully")
                .build();
    }

    /**
     * Forgot-password flow: validates the account status up front and reports it back
     * distinctly (no account found / deleted / deactivated / active) rather than a single
     * generic response — a deliberate product decision to prioritize clear self-service
     * feedback over email-enumeration hardening for this flow. Malformed input is separately
     * rejected by {@code @Email} on {@link com.nforce.onehr.dto.ForgotPasswordRequest} before
     * this method ever runs.
     */
    @Transactional
    public ForgotPasswordResponse forgotPassword(String email, String requestOrigin) {
        String normalizedEmail = email.toLowerCase().trim();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new AccountNotFoundException("No account found with this email address"));

        if (user.getDeletedAt() != null) {
            throw new DisabledException("This account has been deleted. Please contact your HR administrator");
        }
        if (!user.isActive()) {
            throw new DisabledException("This account has been deactivated. Please contact your HR administrator");
        }

        String tempPassword = generateTempPassword();
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        // Invalidates any JWT issued under the old password (see JwtAuthenticationFilter).
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setTokenVersionReason("PASSWORD_CHANGED");
        userRepository.save(user);

        String fullName = employeeRepository.findById(user.getId())
                .map(com.nforce.onehr.entity.Employee::getFullName)
                .orElse(user.getEmail());

        emailService.sendPasswordResetEmail(user.getEmail(), fullName, tempPassword, requestOrigin);
        auditService.log(user.getId(), "PASSWORD_RESET_VIA_FORGOT_FLOW", user.getId());
        // No linkPath: the Password Reset notification intentionally has no "Open related
        // page" action in the Notifications tab (ONEHR-351) — email delivery is unaffected.
        notificationService.send(user.getId(), "SECURITY",
                "Password Reset",
                "Your password was reset via the forgot-password flow. If you didn't request this, contact your HR admin immediately.",
                null);

        return ForgotPasswordResponse.builder()
                .message("Password reset instructions have been sent to your email")
                .build();
    }

    private String generateTempPassword() {
        int digits = 100000 + RANDOM.nextInt(900000);
        return "OneHR@" + digits;
    }
}

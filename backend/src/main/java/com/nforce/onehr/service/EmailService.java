package com.nforce.onehr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class EmailService {

    private static final String RESEND_URL = "https://api.resend.com/emails";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    /** Served as a static asset (frontend/public/nforce-logo.png, same source BrandMark.tsx uses)
     * and linked by URL rather than inlined as base64 — most mail clients (Gmail, Outlook) strip
     * or refuse to render data: URI images in the message body, so a fetchable URL is required
     * for the logo to actually show up in a delivered email. Requires the resolved base URL to be
     * reachable from wherever the recipient opens the email — a local dev base-url won't render
     * for real recipients outside that machine's network. */
    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.from}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:5180}")
    private String baseUrl;

    /** Same per-environment allowlist CORS is configured with — reused here so the password-reset
     * link can safely echo back whichever environment's origin actually made the request, instead
     * of always pointing at this instance's own default base URL. */
    @Value("${app.cors.allowed-origins:http://localhost:5180}")
    private String allowedOriginsConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void sendInviteEmail(String toEmail, String fullName, String tempPassword) {
        String subject = "Welcome to NForce OneHR — your account is ready";
        String html = buildInviteHtml(fullName, toEmail, tempPassword);
        sendAsync(toEmail, subject, html);
    }

    public void sendPasswordResetEmail(String toEmail, String fullName, String tempPassword, String requestOrigin) {
        String subject = "NForce OneHR — your password has been reset";
        String resolvedBaseUrl = resolveBaseUrl(requestOrigin);
        log.info("Password-reset email for {}: request Origin={}, resolved link base={}", toEmail, requestOrigin, resolvedBaseUrl);
        String html = buildResetHtml(fullName, toEmail, tempPassword, resolvedBaseUrl);
        sendAsync(toEmail, subject, html);
    }

    /** Echoes back the requesting environment's own origin (validated against the same
     * allowlist CORS uses) so the emailed link returns to wherever the request came from —
     * local, Dev, or any other deployed environment — instead of a fixed default. Falls back
     * to {@code app.base-url} when there's no Origin header or it isn't on the allowlist. */
    private String resolveBaseUrl(String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return baseUrl;
        }
        String normalizedOrigin = stripTrailingSlash(requestOrigin.trim());
        boolean allowed = java.util.Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .anyMatch(o -> stripTrailingSlash(o).equalsIgnoreCase(normalizedOrigin));
        return allowed ? normalizedOrigin : baseUrl;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendLateArrivalEmail(String toEmail, String ccEmail, String fullName, LocalDate date,
                                      LocalTime expectedTime, LocalTime actualTime, Integer minutesLate) {
        String subject = "Late arrival recorded — " + DATE_FMT.format(date);
        String html = buildLateArrivalHtml(fullName, date, expectedTime, actualTime, minutesLate);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendMissingPunchEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Missing check-out — " + DATE_FMT.format(date);
        String html = buildMissingPunchHtml(fullName, date, checkInTime);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    /** ccEmail (the employee's current manager) is optional — omitted if there is none on file. */
    public void sendLeaveAttendanceConflictEmail(String toEmail, String ccEmail, String fullName, LocalDate date, LocalTime checkInTime) {
        String subject = "Attendance recorded during approved leave — " + DATE_FMT.format(date);
        String html = buildLeaveAttendanceConflictHtml(fullName, date, checkInTime);
        sendAsync(toEmail, ccEmail, subject, html);
    }

    private void sendAsync(String to, String subject, String html) {
        sendAsync(to, null, subject, html);
    }

    private void sendAsync(String to, String cc, String subject, String html) {
        String ccField = cc == null ? "" : """
                ,
                  "cc": ["%s"]""".formatted(cc);
        String body = """
                {
                  "from": "%s",
                  "to": ["%s"]%s,
                  "subject": "%s",
                  "html": %s
                }
                """.formatted(fromAddress, to, ccField, subject, jsonString(html));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RESEND_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        log.info("Email sent to {} cc {} (subject: {})", to, cc, subject);
                    } else {
                        log.error("Resend API error: status={} body={}", res.statusCode(), res.body());
                    }
                })
                .exceptionally(ex -> {
                    log.error("Failed to send email to {} — underlying action was not affected: {}", to, ex.getMessage());
                    return null;
                });
    }

    private String buildInviteHtml(String fullName, String email, String tempPassword) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <!-- Header -->
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <!-- Body -->
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Your account is ready</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, welcome to NForce OneHR. Your account has been created and you can log in below.</p>

                          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#1E2128;border:1px solid #2A2E37;border-radius:8px;margin-bottom:28px;">
                            <tr><td style="padding:20px 24px;">
                              <p style="margin:0 0 10px;font-size:12px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:.06em;">Your login credentials</p>
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Email: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0;font-size:13px;color:#9BA1AC;">Temp password: <span style="font-family:'Courier New',monospace;background:#080808;color:#E8EAED;padding:3px 8px;border-radius:4px;font-size:14px;font-weight:600;">%s</span></p>
                            </td></tr>
                          </table>

                          <div style="background:rgba(228,55,61,.08);border:1px solid rgba(228,55,61,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#f4a5a8;line-height:1.5;">You will be required to set your own password on first login. Keep this email private.</p>
                          </div>

                          <a href="%s/login" style="display:inline-block;background:#B11116;color:#ffffff;font-weight:700;font-size:14px;text-decoration:none;padding:12px 28px;border-radius:8px;">Sign in to OneHR →</a>
                        </td></tr>
                        <!-- Footer -->
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR. If you didn't expect this, contact your HR administrator.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, email, tempPassword, baseUrl);
    }

    private String buildResetHtml(String fullName, String email, String tempPassword, String baseUrl) {
        // Carries the email as a query param so the linked page (ResetPasswordPage in the
        // frontend) never has to ask the user to type it again — see forgot-password flow.
        String resetLink = baseUrl + "/reset-password?email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">
                </head>
                <body bgcolor="#f4f4f4" style="margin:0;padding:0;background-color:#f4f4f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f4;padding:40px 16px;">
                    <tr><td align="center">
                      <table bgcolor="#16181d" width="440" cellpadding="0" cellspacing="0" style="background-color:#16181d;border-radius:6px;overflow:hidden;color:#e0e0e0;">
                        <tr><td style="background:linear-gradient(90deg, #A01418 0%%, #A01418 30%%, #050506 100%%);background-color:#A01418;padding:8px 30px;">
                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation"><tr>
                            <td valign="middle" align="left" style="width:35px;">
                              <table cellpadding="0" cellspacing="0" role="presentation" style="width:35px;height:35px;">
                                <tr><td align="center" valign="middle" bgcolor="#000000" style="width:35px;height:35px;background-color:#000000;border:2px solid #333333;border-radius:50%%;overflow:hidden;">
                                  <img src="%s/nforce-logo.png" width="35" height="35" alt="" style="display:block;border-radius:50%%;border:0;">
                                </td></tr>
                              </table>
                            </td>
                            <td valign="middle" align="right" style="color:#ffffff;font-weight:700;font-size:16px;">NForce OneHR</td>
                          </tr></table>
                        </td></tr>
                        <tr><td style="padding:24px 30px;">
                          <h2 style="color:#ffffff;margin:0 0 14px;font-size:23px;font-weight:700;">Reset your password</h2>
                          <p style="line-height:1.5;margin:0 0 16px;font-size:14px;color:#d1d5db;">Hi %s, a password reset was requested for %s. Use the temporary password below to sign in.</p>

                          <table cellpadding="0" cellspacing="0" role="presentation" style="margin:0 0 20px;">
                            <tr>
                              <td style="font-size:14px;color:#9ca3af;">Password:</td>
                              <td style="padding-left:12px;">
                                <span style="display:inline-block;background-color:#1f2229;padding:6px 14px;border-radius:6px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:15px;color:#ffffff;font-weight:bold;border:1px solid #2d313a;">%s</span>
                              </td>
                            </tr>
                          </table>

                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="background-color:#2d2216;border-radius:4px;margin-bottom:23px;">
                            <tr><td style="border-left:3px solid #d97706;padding:12px 16px;">
                              <p style="margin:0 0 4px;font-size:13px;font-weight:700;color:#f59e0b;">⚠️ Temporary Password Notice</p>
                              <p style="margin:0;font-size:13px;color:#9ca3af;line-height:1.4;">This password is for one-time use only. Enter it on the next page along with your new password to finish resetting your account.</p>
                            </td></tr>
                          </table>

                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation">
                            <tr><td>
                              <a href="%s" style="display:block;width:100%%;box-sizing:border-box;background-color:#b91c1c;color:#ffffff;text-align:center;padding:6.5px 12px;border-radius:6px;text-decoration:none;font-weight:600;font-size:15px;">Reset your password →</a>
                            </td></tr>
                          </table>

                          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="border-top:1px solid #2d313a;margin-top:20px;">
                            <tr><td style="padding-top:14px;font-size:11px;color:#6b7280;line-height:1.4;">
                              This email was sent by NForce OneHR. If you didn't request this reset, contact your HR administrator immediately.
                            </td></tr>
                          </table>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(baseUrl, fullName, email, tempPassword, resetLink);
    }

    private String buildLateArrivalHtml(String fullName, LocalDate date, LocalTime expectedTime,
                                         LocalTime actualTime, Integer minutesLate) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Late arrival recorded</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, your check-in on %s was recorded after the shift start grace period.</p>

                          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#1E2128;border:1px solid #2A2E37;border-radius:8px;margin-bottom:28px;">
                            <tr><td style="padding:20px 24px;">
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Expected: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0 0 6px;font-size:13px;color:#9BA1AC;">Actual check-in: <span style="color:#E8EAED;font-weight:600;">%s</span></p>
                              <p style="margin:0;font-size:13px;color:#9BA1AC;">Minutes late: <span style="color:#E8EAED;font-weight:600;">%d</span></p>
                            </td></tr>
                          </table>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date),
                        TIME_FMT.format(expectedTime), TIME_FMT.format(actualTime), minutesLate);
    }

    private String buildMissingPunchHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Missing check-out</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, we noticed you checked in on %s at %s but never checked out.</p>

                          <div style="background:rgba(224,169,59,.08);border:1px solid rgba(224,169,59,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#E0A93B;line-height:1.5;">Please submit an Attendance Regularization request with your correct check-out time so your manager or HR can review it.</p>
                          </div>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date), TIME_FMT.format(checkInTime));
    }

    private String buildLeaveAttendanceConflictHtml(String fullName, LocalDate date, LocalTime checkInTime) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#080808;font-family:Inter,Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080808;padding:40px 16px;">
                    <tr><td align="center">
                      <table width="560" cellpadding="0" cellspacing="0" style="background:#16181D;border:1px solid #2A2E37;border-radius:12px;overflow:hidden;">
                        <tr><td style="background:#B11116;padding:28px 36px;">
                          <span style="font-family:'Space Grotesk',Arial,sans-serif;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-0.02em;">NForce OneHR</span>
                        </td></tr>
                        <tr><td style="padding:36px;">
                          <h1 style="font-family:'Space Grotesk',Arial,sans-serif;font-size:22px;font-weight:700;color:#E8EAED;margin:0 0 8px;">Attendance recorded during approved leave</h1>
                          <p style="color:#9BA1AC;font-size:14px;line-height:1.6;margin:0 0 28px;">Hi %s, you have an approved leave covering %s, but a check-in at %s was also recorded that day.</p>

                          <div style="background:rgba(228,55,61,.08);border:1px solid rgba(228,55,61,.2);border-radius:8px;padding:14px 18px;margin-bottom:28px;">
                            <p style="margin:0;font-size:13px;color:#f4a5a8;line-height:1.5;">If this was unintentional, contact your manager or HR administrator to correct your attendance or leave record.</p>
                          </div>

                          <p style="color:#6B7280;font-size:12px;line-height:1.5;margin:0;">This is an automated notice. If you believe this is incorrect, contact your manager or HR administrator.</p>
                        </td></tr>
                        <tr><td style="padding:20px 36px;border-top:1px solid #2A2E37;">
                          <p style="margin:0;font-size:12px;color:#6B7280;">This email was sent by NForce OneHR.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(fullName, DATE_FMT.format(date), TIME_FMT.format(checkInTime));
    }

    private String jsonString(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}

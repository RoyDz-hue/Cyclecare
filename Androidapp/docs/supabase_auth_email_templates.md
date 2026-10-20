# CycleCare Supabase Auth Links and Providers

CycleCare uses Supabase Auth for email/password accounts, verification emails, passwordless magic links, password recovery, and Google OAuth. The Supabase Auth user email is the identity; there is no separate admin mailbox requirement. Supabase sends the Auth emails using the configured mail transport, while these templates control their branding and content.

The email templates use Supabase’s `{{ .ConfirmationURL }}`. Android Auth requests set `redirect_to=cyclecare://auth/callback`; Supabase verifies the one-time token and then redirects to that URI. The Android manifest registers the native callback and the app validates the returned session before allowing access. The admin PWA uses the PKCE OAuth flow and completes the code exchange in the PWA before checking the server-side admin role.

## Dashboard settings

1. In **Authentication → URL Configuration**, keep the deployed website as the project’s Site URL. Add `cyclecare://auth/callback` and the exact deployed admin PWA callback URL to the **Redirect URLs** allow-list. For local development, allow the exact local origin/path you use; the PWA can override its callback with `VITE_ADMIN_OAUTH_REDIRECT`.
2. In **Authentication → Email Templates**, use subject `Verify your CycleCare account` and `supabase/templates/confirm-signup.html` for Confirm signup; use `Your CycleCare sign-in link` and `supabase/templates/magic-link.html` for Magic Link; use `Reset your CycleCare password` and `supabase/templates/password-recovery.html` for Reset Password.
3. Keep the email link variable as `{{ .ConfirmationURL }}`. Do not replace it with a hard-coded `cyclecare://` URL: Supabase’s verification URL must first consume the one-time token and only then redirect to the app.
4. For Google sign-in, enable **Google** under **Authentication → Providers** and enter the Google OAuth client ID and client secret there. These are Auth provider credentials—not Supabase Edge Function secrets and not PWA environment values. In Google Cloud, set the authorized redirect URI to `https://apjqcocxtylsofgxhetb.supabase.co/auth/v1/callback`. Supabase must then allow the Android callback and the PWA URL above. The admin PWA will still reject any Google account that does not pass `is_cyclecare_admin()`.
5. Test email signup, magic-link sign-in, guest-account email linking, password recovery, and Google sign-in on a device with CycleCare installed. New email registrations must remain outside the app until the verification callback succeeds.

The app-scoped device ID is a random, persistent installation UUID stored in encrypted preferences and synced as `profiles.device_id`; no IMEI, SIM serial, or other restricted hardware identifier is collected. A production SMTP provider can be configured separately for reliable delivery/branding of Auth emails; it is not another user account.

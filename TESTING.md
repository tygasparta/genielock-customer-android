# TESTING.md — GenieLock Customer

Manual checklist to run through on a real device before shipping a build.

## Sign-in & session

- [ ] Sign in with customer number or phone + SMS code (Omniflex).
- [ ] Close the app (not just background it) and reopen — still signed in, no re-login.

## Knox lock screen

- [ ] Lock the device from the Knox Guard console.
- [ ] Open GenieLock Customer from the lock screen — it opens **over** the lock screen,
      no Chrome window, no need to unlock the phone first.

## Payment

- [ ] While locked, make a $1 EcoCash payment — PIN prompt appears, payment completes,
      receipt shows in the app.
- [ ] Trigger Knox's "refresh account status" (or wait for its normal refresh cycle) —
      device unlocks.

## Voucher / camera

- [ ] Scan a voucher QR code with the camera (grant the camera permission prompt the
      first time).
- [ ] Scan a voucher QR from an image instead of the live camera (file/image picker path).

## Downloads

- [ ] Download a receipt PDF — appears in Downloads (or the app's own files area on
      Android 8–9, see the README note on storage permissions) and opens correctly.
- [ ] Download the agreement PDF the same way.

## Connectivity

- [ ] Turn on airplane mode mid-session — offline screen appears with GenieLock branding
      and a Retry button.
- [ ] Turn connectivity back on — app detects it automatically and reloads without
      tapping Retry.
- [ ] Tap Retry manually while still offline — shows "still no connection" instead of
      crashing or hanging.

## Navigation & chrome

- [ ] Back button inside the portal goes back a page; back button on the first page
      backgrounds the app instead of closing/killing it.
- [ ] Pull-to-refresh at the top of a page reloads it.
- [ ] Tap a WhatsApp (`wa.me`), `tel:`, or `mailto:` link in the portal — opens the
      relevant external app, not inside the WebView.
- [ ] Tapping an external link while the phone is locked fails gracefully (toast, no
      crash) if the OS blocks launching another app from the lock screen.

## Security sanity checks

- [ ] Visiting any non-`my.gadgetgenie.org` URL never loads inside the WebView.
- [ ] A deliberately broken HTTPS cert (e.g. via a proxy) is rejected, not click-through.

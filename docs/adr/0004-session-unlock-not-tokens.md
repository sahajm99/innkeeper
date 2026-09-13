# ADR 0004: Booking pages open by confirmation code plus email, remembered in the session

Status: accepted (2026-09-12)

## Context

Guests book without an account. A confirmation page, an invoice and a cancel button must be
reachable later, and a confirmation code alone (six characters from a 32-letter alphabet) should
not expose someone else's stay to a guesser.

## Options

1. Code in the URL, no further check. A guessable URL shows a stranger's name and email.
2. An HMAC-signed token issued at booking time, carried in the URL for pages and the API. Secure,
   but a token scheme to implement, rotate and explain, and the token lives in the URL and logs.
3. Code plus the booking email. Creating a booking or looking it up with both unlocks the code in
   the HTTP session; staff roles see everything; a logged-in guest account sees bookings for its
   email. The API takes the pair in a POST body.

## Decision

Option 3. The email is checked before anything else, so an unknown code and a wrong email are
indistinguishable (no code oracle), and the request log never records query strings. Lookups are
rate limited like bookings.

## Consequences

- A session that expires or a nightly reset sends the guest back to the lookup page with the code
  prefilled; the confirmation page tells guests to keep both the code and the email.
- Sessions are in memory; a restart of the free-tier instance forgets them, which the lookup page
  covers.
- The API has no GET-by-code endpoint; `POST /api/bookings/lookup` carries the pair in the body.

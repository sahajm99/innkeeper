#!/usr/bin/env bash
# Smoke check against a running Innkeeper: health, then create, look up and cancel a booking
# through the JSON API. Usage: scripts/smoke.sh https://host
set -euo pipefail
BASE="${1:?usage: smoke.sh https://host}"
BASE="${BASE%/}"

code=""
for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health" || true)
  [ "$code" = "200" ] && break
  sleep 5
done
[ "$code" = "200" ] || { echo "health check never returned 200 (last: $code)"; exit 1; }
echo "health: 200"

check_in=$(date -u -d '+100 days' +%F)
check_out=$(date -u -d '+102 days' +%F)
room=$(curl -sf "$BASE/api/rooms?branchId=1&checkIn=$check_in&checkOut=$check_out&guests=1" | jq -r '.[0].id')
[ -n "$room" ] && [ "$room" != "null" ] || { echo "no available room returned"; exit 1; }

booking=$(curl -sf -X POST "$BASE/api/bookings" -H 'content-type: application/json' \
  -d "{\"roomId\":$room,\"checkIn\":\"$check_in\",\"checkOut\":\"$check_out\",\"adults\":1,\"children\":0,\"firstName\":\"Smoke\",\"lastName\":\"Test\",\"email\":\"smoke@example.com\"}")
confirmation=$(echo "$booking" | jq -r '.code')
[[ "$confirmation" =~ ^INN-[A-Z2-9]{6}$ ]] || { echo "unexpected booking response: $booking"; exit 1; }
echo "booked: $confirmation (room $room, $check_in to $check_out)"

curl -sf -X POST "$BASE/api/bookings/lookup" -H 'content-type: application/json' \
  -d "{\"code\":\"$confirmation\",\"email\":\"smoke@example.com\"}" | jq -e '.status == "CONFIRMED"' >/dev/null
echo "lookup: CONFIRMED"

curl -sf -X POST "$BASE/api/bookings/$confirmation/cancel" -H 'content-type: application/json' \
  -d '{"email":"smoke@example.com"}' | jq -e '.status == "CANCELLED"' >/dev/null
echo "cancel: CANCELLED"
echo "smoke ok"

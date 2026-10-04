#!/bin/bash
# Scenario: SLA Breach — ShippingAgent SLA_BREACH Path
# Step 2: Capture Payment
#
# Payment triggers order processing. Once enriched, fulfillment.Order calls
# ShippingAgent via Nexus. Watch the fulfillment namespace in Temporal UI:
#
#   get_carrier_rates       — fetches fixture-backed rates under the workshop margin
#   get_location_events     — origin + destination SCRM (concurrent)
#   finalize_recommendation — REJECTED (find_alternate_warehouse not called yet)
#   find_alternate_warehouse — returns empty (no alternate in seed data)
#   finalize_recommendation — ACCEPTED with outcome=SLA_BREACH
#
# SLA_BREACH: no fixture-backed rate can satisfy the same-day delivery SLA.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_resume "$SCRIPT_DIR"

EVENT_JSON="$(scenario_payment_event_json)"

echo "Capturing payment for ${ORDER_ID}..."
echo "Customer ID: ${CUSTOMER_ID}"
echo ""

xh POST http://localhost:8080/api/v1/payments-app/orders \
  --raw "${EVENT_JSON}"

echo ""
echo "Payment captured — order is now processing"
echo ""
echo "Watch Temporal UI (fulfillment namespace) for:"
echo "  ShippingAgent workflow ID: ${CUSTOMER_ID}"
echo "  Outcome: SLA_BREACH after find_alternate_warehouse returns empty"
echo ""
echo "Note: delivery_days=0 with paid_price_cents=995 — fixture rates stay under"
echo "the workshop margin, but none can satisfy same-day delivery."

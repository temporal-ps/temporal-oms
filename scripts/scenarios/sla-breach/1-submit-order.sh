#!/bin/bash
# Scenario: SLA Breach — ShippingAgent SLA_BREACH Path
# Step 1: Submit Order
#
# delivery_days=0 sets a same-day SLA — impossible for any carrier to meet.
# paid_price_cents=995 ($9.95) matches the normal route's workshop margin.
# No carrier delivers in 0 days, so SLA_BREACH is always triggered.
#
# The SLA rule in the system prompt reads:
#   "If no rate that costs at or below the customer paid price delivers within
#    N days, outcome MUST be SLA_BREACH."
#
# The agent must call find_alternate_warehouse before finalizing SLA_BREACH.
# Watch for it in the fulfillment namespace workflow history in Temporal UI.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_begin "$SCRIPT_DIR"

EVENT_JSON="$(scenario_commerce_event_json"11 Wall St" "New York" "NY" "10005" "995" "0")"

echo "Submitting order with same-day SLA to trigger SLA_BREACH..."
echo "Workflow ID: ${ORDER_ID}"
echo "Customer ID: ${CUSTOMER_ID}"
echo "Run context: ${SCENARIO_CONTEXT_FILE}"
echo ""

xh PUT "http://localhost:8080/api/v1/commerce-app/orders/${ORDER_ID}" \
  --raw "${EVENT_JSON}"

echo ""
echo "Order submitted"
echo "Next step: Run 2-capture-payment.sh"

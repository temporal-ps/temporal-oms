#!/bin/bash
# Scenario: Complete Invalid Order
# Step 2: Capture Payment
# This captures payment for the order
# Order processing will begin and trigger validation failure

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_resume "$SCRIPT_DIR"

EVENT_JSON="$(scenario_payment_event_json)"

echo "💳 Capturing payment..."
echo "Order ID: ${ORDER_ID}"
echo "Customer ID: ${CUSTOMER_ID}"
echo ""

xh POST http://localhost:8080/api/v1/payments-app/orders \
  --raw "${EVENT_JSON}"

echo ""
echo "✅ Payment captured"
echo "⏳ Order is now processing and will trigger validation failure"
echo "Next step: Run 3-complete-validation.sh (after observing the validation failure)"

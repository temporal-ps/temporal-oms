#!/bin/bash
# Scenario: Complete Valid Order
# Step 2: Capture Payment
# This captures payment and triggers order processing
# The order will pass validation and proceed to enrichment and fulfillment

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
echo "🚀 Order is now processing and will automatically pass validation"
echo ""
echo "Demo complete! The order is moving through the workflow:"
echo "  1. Order validated ✓"
echo "  2. Order enriched (inventory lookup, brand codes, etc.)"
echo "  3. Order fulfilled (shipped)"
echo ""
echo "Check Temporal UI to see the complete automated order flow."

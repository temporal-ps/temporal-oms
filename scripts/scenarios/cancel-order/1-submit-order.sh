#!/bin/bash
# Scenario: Canceling an Order
# Step 1: Submit Order
# This creates the workflow instance with an order ready to be canceled

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_begin "$SCRIPT_DIR"

EVENT_JSON="$(scenario_commerce_event_json"11 Wall St" "New York" "NY" "10005" "995" "5")"

echo "📋 Submitting order for cancellation scenario..."
echo "Workflow ID: ${ORDER_ID}"
echo "Customer ID: ${CUSTOMER_ID}"
echo "Run context: ${SCENARIO_CONTEXT_FILE}"
echo ""

xh PUT "http://localhost:8080/api/v1/commerce-app/orders/${ORDER_ID}" \
  --raw "${EVENT_JSON}"

echo ""
echo "✅ Order submitted successfully"
echo "Next step: Run 2-cancel-order.sh"

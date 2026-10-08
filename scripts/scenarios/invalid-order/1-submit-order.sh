#!/bin/bash
# Scenario: Complete Invalid Order
# Step 1: Submit Order (Invalid)
# This submits an order that will fail validation
# The order ID contains "invalid" which triggers validation failure in the system

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_begin "$SCRIPT_DIR"

EVENT_JSON="$(scenario_commerce_event_json"11 Wall St" "New York" "NY" "10005" "995" "5")"

echo "📋 Submitting INVALID order for processing..."
echo "Workflow ID: ${ORDER_ID}"
echo "Customer ID: ${CUSTOMER_ID}"
echo "Note: This order will fail validation and require manual correction"
echo "Run context: ${SCENARIO_CONTEXT_FILE}"
echo ""

xh PUT "http://localhost:8080/api/v1/commerce-app/orders/${ORDER_ID}" \
  --raw "${EVENT_JSON}"

echo ""
echo "✅ Order submitted"
echo "Next step: Run 2-capture-payment.sh"

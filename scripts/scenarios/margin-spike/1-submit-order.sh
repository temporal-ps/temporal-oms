#!/bin/bash
# Scenario: Margin Spike — ShippingAgent Alternate Warehouse Path
# Step 1: Submit Order
#
# paid_price_cents=1 (1 cent) guarantees every fixture-backed shipping rate exceeds the
# customer paid price, triggering MARGIN_SPIKE in the ShippingAgent.
# The agent must call find_alternate_warehouse before finalizing — watch for it
# in the fulfillment namespace workflow history in Temporal UI.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib.sh"
scenario_begin "$SCRIPT_DIR"

ORDER_JSON="$(scenario_order_json "11 Wall St" "New York" "NY" "10005" "1")"

echo "Submitting order with 1-cent paid price to trigger MARGIN_SPIKE..."
echo "Workflow ID: ${ORDER_ID}"
echo "Customer ID: ${CUSTOMER_ID}"
echo "Run context: ${SCENARIO_CONTEXT_FILE}"
echo ""

xh PUT "http://localhost:8080/api/v1/commerce-app/orders/${ORDER_ID}" \
  customerId="${CUSTOMER_ID}" \
  order:="${ORDER_JSON}"

echo ""
echo "Order submitted"
echo "Next step: Run 2-capture-payment.sh"

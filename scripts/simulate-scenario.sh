#!/usr/bin/env bash
# Trigger one DemoScenario end-to-end against the Commerce App / Payments
# Processor REST surface, without opening the Svelte UI or the load generator.
#
# Usage: scripts/simulate-scenario.sh <SCENARIO> [CARD_NUMBER]
#   SCENARIO: NORMAL | PAYMENT_BEFORE_COMMERCE | MISSING_COMMERCE_EVENT | MISSING_PAYMENT_EVENT
#   CARD_NUMBER: optional; defaults to an unrecognized (approved) test card
set -euo pipefail

BASE_URL="${ENABLEMENTS_API_BASE_URL:-http://localhost:8050}"
SCENARIO="${1:?Usage: $0 <SCENARIO> [CARD_NUMBER]}"
CARD_NUMBER="${2:-4242424242424242}"

case "$SCENARIO" in
  NORMAL|PAYMENT_BEFORE_COMMERCE|MISSING_COMMERCE_EVENT|MISSING_PAYMENT_EVENT) ;;
  *)
    echo "Unknown scenario: $SCENARIO" >&2
    echo "Expected one of: NORMAL, PAYMENT_BEFORE_COMMERCE, MISSING_COMMERCE_EVENT, MISSING_PAYMENT_EVENT" >&2
    exit 1
    ;;
esac

CUSTOMER_ID="sim-customer-$$"

echo "Creating commerce order (scenario=$SCENARIO)..."
ORDER_JSON=$(curl -sS -X POST "$BASE_URL/api/v1/integrations/commerce/orders" \
  -H "Content-Type: application/json" \
  -d "{
    \"customerId\": \"$CUSTOMER_ID\",
    \"items\": [{\"itemId\": \"item-1\", \"quantity\": 1}],
    \"shippingAddress\": {\"easypost\": {\"street1\": \"388 Townsend St\", \"city\": \"San Francisco\", \"state\": \"CA\", \"zip\": \"94107\", \"country\": \"US\"}},
    \"scenarioOptions\": {\"scenario\": \"$SCENARIO\"}
  }")
ORDER_ID=$(echo "$ORDER_JSON" | grep -o '"orderId":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "  order_id=$ORDER_ID"

echo "Creating charge (card=$CARD_NUMBER)..."
CHARGE_JSON=$(curl -sS -X POST "$BASE_URL/api/v1/integrations/payments/charges" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": \"$ORDER_ID\",
    \"customerId\": \"$CUSTOMER_ID\",
    \"amountCents\": 2500,
    \"cardNumber\": \"$CARD_NUMBER\"
  }")
CHARGE_ID=$(echo "$CHARGE_JSON" | grep -o '"chargeId":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "  charge_id=$CHARGE_ID"
echo "  charge status: $(echo "$CHARGE_JSON" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)"

echo
echo "PublishCartOrders delivers this order's events on its next scheduled tick."
echo "Inspect delivery with:"
echo "  curl -sS $BASE_URL/api/v1/integrations/webhooks/events"

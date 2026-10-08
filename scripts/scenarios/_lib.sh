#!/bin/bash

scenario_init() {
  SCENARIO_DIR="$1"
  SCENARIO_NAME="$(basename "$SCENARIO_DIR")"
  SCENARIO_STATE_DIR="${SCENARIO_STATE_DIR:-${TMPDIR:-/tmp}/fde-temporal-oms-scenarios}"
  SCENARIO_CONTEXT_FILE="${SCENARIO_STATE_DIR}/${SCENARIO_NAME}.env"
  mkdir -p "$SCENARIO_STATE_DIR"
}

scenario_generate_order_id() {
  local ts
  ts="$(date +%Y%m%d%H%M%S)"
  printf "%s-%s-%s" "$SCENARIO_NAME" "$ts" "$$"
}

scenario_generate_customer_id() {
  printf "cust-%s" "$ORDER_ID"
}

scenario_save_context() {
  {
    printf "ORDER_ID=%q\n" "$ORDER_ID"
    printf "CUSTOMER_ID=%q\n" "$CUSTOMER_ID"
    printf "PAYMENT_RRN=%q\n" "$PAYMENT_RRN"
    printf "PAYMENT_AMOUNT_CENTS=%q\n" "$PAYMENT_AMOUNT_CENTS"
  } > "$SCENARIO_CONTEXT_FILE"
}

scenario_begin() {
  local scenario_dir="$1"

  scenario_init "$scenario_dir"
  ORDER_ID="${ORDER_ID:-$(scenario_generate_order_id)}"
  CUSTOMER_ID="${CUSTOMER_ID:-$(scenario_generate_customer_id)}"
  PAYMENT_RRN="${PAYMENT_RRN:-payment-${ORDER_ID}}"
  PAYMENT_AMOUNT_CENTS="${PAYMENT_AMOUNT_CENTS:-9999}"
  export ORDER_ID CUSTOMER_ID PAYMENT_RRN PAYMENT_AMOUNT_CENTS
  scenario_save_context
}

scenario_resume() {
  local scenario_dir="$1"

  scenario_init "$scenario_dir"
  if [ -z "${ORDER_ID:-}" ]; then
    if [ ! -f "$SCENARIO_CONTEXT_FILE" ]; then
      echo "No saved run context for ${SCENARIO_NAME}."
      echo "Run 1-submit-order.sh first, set ORDER_ID explicitly, or use ./run.sh."
      exit 1
    fi
    # shellcheck disable=SC1090
    source "$SCENARIO_CONTEXT_FILE"
  fi

  CUSTOMER_ID="${CUSTOMER_ID:-$(scenario_generate_customer_id)}"
  PAYMENT_RRN="${PAYMENT_RRN:-payment-${ORDER_ID}}"
  PAYMENT_AMOUNT_CENTS="${PAYMENT_AMOUNT_CENTS:-9999}"
  export ORDER_ID CUSTOMER_ID PAYMENT_RRN PAYMENT_AMOUNT_CENTS
}

scenario_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

# Prints a CommerceOrderEvent (commerce.order.submitted) for ORDER_ID. The event ID is
# derived from ORDER_ID, so re-running a step is deduplicated by apps-api.
scenario_commerce_event_json() {
  local street="$1"
  local city="$2"
  local state="$3"
  local postal_code="$4"
  local paid_price_cents="$5"
  local delivery_days="${6:-}"
  local rate=""

  if [ -n "$delivery_days" ]; then
    rate=$(printf ',"easypost":{"selectedRate":{"deliveryDays":"%s"}}' "$delivery_days")
  fi

  # APRL-001 (Blue T-Shirt in commerce-catalog.json) carries the "APRL-" warehouse-routing
  # prefix the fulfillment shipping fixture expects; see shipping-fixtures.json warehouses.
  printf '{"eventId":"evt-commerce-%s","type":"commerce.order.submitted","created":"%s","order":{"orderId":"%s","customerId":"%s","items":[{"itemId":"APRL-001","quantity":1}],"shippingAddress":{"easypost":{"street1":"%s","city":"%s","state":"%s","zip":"%s","country":"US"}},"selectedShipment":{"paidPrice":{"units":"%s","currency":"USD"}%s},"status":"PLACED"}}' \
    "$ORDER_ID" "$(scenario_now)" "$ORDER_ID" "$CUSTOMER_ID" "$street" "$city" "$state" "$postal_code" "$paid_price_cents" "$rate"
}

# Prints a PaymentEvent (payment.captured) for ORDER_ID, using PAYMENT_RRN as the charge ID.
scenario_payment_event_json() {
  printf '{"eventId":"evt-payment-captured-%s","type":"payment.captured","created":"%s","charge":{"chargeId":"%s","orderId":"%s","customerId":"%s","amountCents":"%s","status":"CAPTURED"}}' \
    "$ORDER_ID" "$(scenario_now)" "$PAYMENT_RRN" "$ORDER_ID" "$CUSTOMER_ID" "$PAYMENT_AMOUNT_CENTS"
}

scenario_wait_for_continue() {
  local yes="$1"
  local pause_seconds="$2"
  local next_step="$3"

  if [ "$yes" -eq 1 ]; then
    echo ""
    echo "Continuing to ${next_step} in ${pause_seconds}s..."
    sleep "$pause_seconds"
  else
    echo ""
    read -r -p "Press Enter to continue to ${next_step}, or Ctrl-C to stop here. "
  fi
}

scenario_run_steps() {
  local scenario_dir="$1"
  shift

  local yes=0
  local pause_seconds=3
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -y|--yes)
        yes=1
        shift
        ;;
      --pause-seconds)
        pause_seconds="$2"
        shift 2
        ;;
      -h|--help)
        echo "Usage: ./run.sh [--yes] [--pause-seconds N]"
        echo ""
        echo "Runs every step in this scenario with one generated workflow ID."
        echo "Without --yes, prompts before each next step."
        return 0
        ;;
      --)
        shift
        break
        ;;
      *)
        break
        ;;
    esac
  done

  local steps=("$@")
  if [ "${#steps[@]}" -eq 0 ]; then
    echo "No scenario steps provided."
    return 1
  fi

  scenario_begin "$scenario_dir"
  echo "Scenario: ${SCENARIO_NAME}"
  echo "Workflow ID: ${ORDER_ID}"
  echo "Customer ID: ${CUSTOMER_ID}"
  echo "Run context: ${SCENARIO_CONTEXT_FILE}"

  local i
  for i in "${!steps[@]}"; do
    echo ""
    echo "==> ${steps[$i]}"
    "${scenario_dir}/${steps[$i]}"
    if [ "$i" -lt "$((${#steps[@]} - 1))" ]; then
      scenario_wait_for_continue "$yes" "$pause_seconds" "${steps[$((i + 1))]}"
    fi
  done
}

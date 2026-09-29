# ShippingAgent — Progress Tracking

**Feature:** `ShippingAgent` — AI-Powered Shipping Rate Selection
**Status:** ✅ Implemented for fixture-backed workshop path; live location-events enrichment deferred
**Owner:** Temporal FDE Team
**Created:** 2026-04-15
**Updated:** 2026-04-29

---

## Phase Status

| Phase | Description | Status | Blocking On |
|-------|-------------|--------|-------------|
| Phase 1 | Proto Schema | ✅ Complete | — |
| Phase 2 | Activity Implementations | ✅ Complete | Phase 1 |
| Phase 3 | ShippingAgent Workflow + Agentic Loop | ✅ Complete | Phase 2 |
| Phase 4 | Nexus Handler + `fulfillment.Order` V2 Wiring | ✅ Complete | `fulfillment.Order` calls ShippingAgent via Nexus |
| Phase 5 | Workshop Scenarios + Demo Scripts | ✅ Complete for current scenarios | `valid-order`, `margin-spike`, and `sla-breach` scripts exercise current paths |
| Phase 6 | Alternate Warehouse Path Hardening | ✅ Complete | — |

---

## Open Questions

| Question | Needed By | Status |
|----------|-----------|--------|
| `within_km` default for Workshop demos | Phase 2 | ✅ Resolved: 50km |
| Default `cache_ttl_secs` | Phase 3 | ✅ Resolved: 1800 (30 minutes) |
| `SLA_BREACH` — signal support from ShippingAgent or return to `fulfillment.Order`? | Phase 3 | ✅ Resolved: return to `fulfillment.Order`; agent recommends, caller decides |

---

## Dependencies

- **Inventory Locations spec** — must exist before `lookup_inventory_location` has real data to query. V1 workaround: `python/fulfillment/config/warehouses.toml` static seed (see Q8).
- **`fulfillment.Order` Phases 3–4 complete** — required before Phase 4 (Nexus wiring into `fulfillment.Order` V2)
- **Location-events enrichment** — current first pass returns `RISK_LEVEL_NONE` and empty events
  through `enablements-api`; real enrichment remains a follow-up.

---

## Open Questions for Phases 1–3

Identified during planning; all resolved.

| # | Question | Needed By | Status |
|---|----------|-----------|--------|
| Q1 | `src/worker.py` does not exist but `Dockerfile` runs `python -m src.worker`. Missing placeholder? | Phase 2 — Worker registration | ✅ Create it in Phase 2; it's a gap, not a choice |
| Q2 | Worker process structure: 3 separate containers or one process with 3 `Worker` objects? | Phase 2 — Worker registration | ✅ One process, 3 `Worker` objects started concurrently; consistent with single `Dockerfile`; each `Worker` enforces its own `max_task_queue_activities_per_second` independently |
| Q3 | `call_llm` I/O types: Anthropic SDK types, custom Pydantic mirrors, or plain `dict`? | Phase 2 — `call_llm` | ✅ Use Anthropic SDK types directly — they are already Pydantic models; Temporal's data converter handles them without any wrapper |
| Q4 | Margin threshold source for `MARGIN_SPIKE` condition? | Phase 1 — proto, Phase 3 — system prompt | ✅ Use `selected_shipment.paid_price` from `RecommendShippingOptionRequest`; prompt text calls this the customer paid price. |
| Q5 | EasyPost parcel dimensions missing from `GetCarrierRatesRequest` — activity needs weight/dims | Phase 1 — proto, Phase 2 — `get_carrier_rates` | ✅ Hardcode default parcel for V1 (1 lb, 6×6×4 in); no proto field added; noted inline in activity |
| Q6 | Define new `VerifyShippingAddressRequest/Response` in `shipping_agent.proto` or reuse Java `VerifyAddressRequest/Response` from `workflows.proto`? | Phase 1 — proto | ✅ Reuse existing Java types — `VerifyAddressRequest_p2p` / `VerifyAddressResponse_p2p` already generated from fulfillment-order Phase 1; removes two Phase 1 proto tasks |
| Q7 | Must Java `AddressVerificationImpl` be updated to populate `EasyPostAddress.coordinate` after Phase 1? | Phase 2/3 integration | ✅ Yes — add explicit task to fulfillment-order Phase 6 breakdown; without it `to_address.easypost_address.coordinate` is null and destination SCRM silently fails; block ShippingAgent end-to-end integration testing on it |
| Q8 | Static warehouse seed data path and schema for `lookup_inventory_location` V1? | Phase 2 — `lookup_inventory_location` | ✅ `python/fulfillment/config/warehouses.toml`; schema: `[[warehouses]]` array with `location_id`, `street`, `city`, `state`, `postal_code`, `country`, `sku_prefixes`; path configurable via `WAREHOUSE_CONFIG_PATH` env var |
| Q9 | `continue_as_new` cache size threshold? | Phase 3 — workflow | ✅ Defer entirely for V1 — Workshop scale won't accumulate enough entries; add to follow-up ticket; task removed from Phase 3 breakdown |

---

## Detailed Task Breakdown

### Phase 1 — Proto Schema
> Blocked on: nothing. Must complete before Phase 2 can begin.
> All proto changes in this phase require a single `buf generate` run at the end.

- [x] Add `coordinate: optional acme.common.v1.Coordinate coordinate = 4` to `EasyPostAddress` in `proto/acme/common/v1/values.proto`
  - Cross-dependency: add a corresponding task to fulfillment-order Phase 6 to populate this field in `AddressVerificationImpl`; tracked as a blocking dependency in the Dependencies section above

- [x] Create `proto/acme/common/v1/llm.proto` with vendor-agnostic LLM message types:
  - `LlmRole` enum: `LLM_ROLE_UNSPECIFIED = 0`, `LLM_ROLE_USER = 1`, `LLM_ROLE_ASSISTANT = 2`
  - `LlmStopReason` enum: `LLM_STOP_REASON_UNSPECIFIED = 0`, `LLM_STOP_REASON_END_TURN = 1`, `LLM_STOP_REASON_TOOL_USE = 2`
  - `LlmTextBlock`: `text: string`
  - `LlmToolUseBlock`: `id: string`, `name: string`, `input: google.protobuf.Struct` (generates `Dict[str, Any]`)
  - `LlmToolResultBlock`: `tool_use_id: string`, `content: string`
  - `LlmContentBlock`: `type: string` (discriminator — `"text"` | `"tool_use"` | `"tool_result"`), `text: LlmTextBlock`, `tool_use: LlmToolUseBlock`, `tool_result: LlmToolResultBlock`
    - **No `oneof`**: `protobuf-to-pydantic` v0.3.3.1 assigns `default_factory` to every message field in a `oneof`, making all fields non-None in the generated Pydantic model — field-presence checks are unusable. Use `type` string as explicit discriminator instead; `call_llm` sets it, agentic loop reads it.
  - `LlmMessage`: `role: LlmRole`, `content: repeated LlmContentBlock`
  - `LlmResponse`: `content: repeated LlmContentBlock`, `stop_reason: LlmStopReason`
  - `LlmToolDefinition`: `name: string`, `description: string`, `input_schema: google.protobuf.Struct` (JSON Schema as `Dict[str, Any]`)

- [x] Add `ShippingLineItem` message to `proto/acme/fulfillment/domain/v1/shipping_agent.proto`
  - Fields: `sku_id: string`, `quantity: int32`
  - Note: `FulfillmentItem` in `workflows.proto` has overlapping fields but also carries warehouse/brand fields irrelevant to shipping rate calculation; a simpler dedicated type avoids coupling

- [x] Add `ShippingOption` message to `shipping_agent.proto`
  - Fields: `id: string` (referenced by `recommended_option_id`), `carrier: string`, `service_level: string`, `cost: acme.common.v1.Money`, `estimated_days: int32`, `rate_id: string`
  - Note: mirrors `CarrierRate` from `workflows.proto` but adds `id` for LLM cross-referencing and uses `id` as the stable recommendation pointer; `CarrierRate` from `workflows.proto` is Java-only and not reused here

- [x] Add `RecommendationOutcome` enum and `ShippingRecommendation` message to `shipping_agent.proto`
  - Enum values: `RECOMMENDATION_OUTCOME_UNSPECIFIED = 0`, `PROCEED = 1`, `CHEAPER_AVAILABLE = 2`, `FASTER_AVAILABLE = 3`, `MARGIN_SPIKE = 4`, `SLA_BREACH = 5`
  - `ShippingRecommendation` fields: `outcome: RecommendationOutcome`, `recommended_option_id: string`, `reasoning: string`, `margin_delta_cents: int64`, `origin_risk_level: acme.fulfillment.domain.fulfillment.v1.RiskLevel`, `destination_risk_level: acme.fulfillment.domain.fulfillment.v1.RiskLevel`

- [x] Replace `RecommendShippingOptionRequest` stub in `shipping_agent.proto` with full definition
  - Fields: `order_id: string`, `customer_id: string`, `to_address: acme.common.v1.Address` (easypost_address pre-populated by fulfillment.Order validateOrder), `items: repeated ShippingLineItem`, `selected_shipment: acme.common.v1.Shipment`
  - Remove the current `address` (field 1) and `coordinate` (field 2) stub fields; they are replaced by the fields above

- [x] Replace `RecommendShippingOptionResponse` stub in `shipping_agent.proto` with full definition
  - Fields: `recommendation: ShippingRecommendation`, `options: repeated ShippingOption`, `cache_hit: bool`

- [x] Add `ShippingOptionsResult` and `ShippingOptionsCache` messages to `shipping_agent.proto` (workflow state and `get_options` Query return type)
  - `ShippingOptionsResult`: `recommendation: ShippingRecommendation`, `options: repeated ShippingOption`, `cached_at: google.protobuf.Timestamp`
  - `ShippingOptionsCache`: `results: map<string, ShippingOptionsResult>` (keyed by cache hash)

- [x] Extend `ShippingAgentExecutionOptions` in `shipping_agent.proto` with `cache_ttl_secs: optional int64`

- [x] Add activity request/response messages to `shipping_agent.proto`:
  - `LookupInventoryLocationRequest`: `items: repeated ShippingLineItem`, `location_id: optional string`
  - `LookupInventoryLocationResponse`: `location_id: string`, `address: acme.common.v1.Address`
  - `GetShippingRatesRequest`: `from_easypost_id: string`, `to_easypost_id: string`, `items: repeated ShippingLineItem` — distinct from Java's `GetCarrierRatesRequest` (different structure; no parcel fields — V1 hardcodes default parcel in the activity)
  - `GetShippingRatesResponse`: `shipment_id: string`, `options: repeated ShippingOption`
  - Note: no `VerifyShippingAddressRequest/Response` needed — `verify_address` reuses `VerifyAddressRequest_p2p` / `VerifyAddressResponse_p2p` already generated from `workflows.proto` (fulfillment-order Phase 1)

- [x] Run `buf generate`; verify:
  - `python/generated/pydantic/acme/common/v1/llm_p2p.py` exists and contains `LlmTextBlock`, `LlmToolUseBlock` (with `input: Dict[str, Any]`), `LlmToolResultBlock`, `LlmContentBlock` (with `type: str` and three non-oneof message fields), `LlmMessage`, `LlmResponse`, `LlmToolDefinition` (with `input_schema: Dict[str, Any]`)
  - Pydantic `*_p2p` classes generated for all other new/modified messages: `ShippingLineItem`, `ShippingOption`, `ShippingRecommendation`, `RecommendationOutcome`, `RecommendShippingOptionRequest`, `RecommendShippingOptionResponse`, `ShippingOptionsResult`, `ShippingOptionsCache`, `ShippingAgentExecutionOptions`, `LookupInventoryLocationRequest/Response`, `GetShippingRatesRequest/Response`
  - Updated `EasyPostAddress` Pydantic class includes `coordinate` field
  - Java classes compile in `fulfillment-core` with no errors

---

### Phase 2 — Activity Implementations
> Blocked on: Phase 1 complete (`buf generate` run; Pydantic `*_p2p` types available).
> Phases 2a, 2b, and 2c can be implemented in parallel once Phase 1 is done.
> Phase 2d (worker registration) requires 2a–2c complete.

#### 2a — `fulfillment` task queue activities (no rate limit)

- [x] Add `anthropic` SDK to `python/pyproject.toml` — required by `call_llm`; resolve version constraint before adding

- [x] Runtime shipping activities use the Python `enablements-api` HTTP client; no EasyPost key is
  required for workshop execution

- [x] `python/fulfillment/src/agents/activities/inventory.py`: `LookupInventoryActivities` class
  - `@activity.defn async def lookup_inventory_location(request: LookupInventoryLocationRequest_p2p) -> LookupInventoryLocationResponse_p2p`
  - V1 implementation: load `python/fulfillment/config/warehouses.toml` at startup (path overridable via `WAREHOUSE_CONFIG_PATH` env var); schema: `[[warehouses]]` with `location_id`, `street`, `city`, `state`, `postal_code`, `country`, `sku_prefixes` fields
  - If `request.location_id` is present: return matching warehouse by `location_id`; if absent: return first warehouse whose `sku_prefixes` matches any `sku_id` in the request items (V1 simplification)

- [x] `python/fulfillment/src/agents/activities/llm.py`: `LlmActivities` class
  - `@activity.defn async def call_llm(messages: list[LlmMessage_p2p], tools: list[LlmToolDefinition_p2p]) -> LlmResponse_p2p`
  - I/O uses `common/v1/llm_p2p` types — vendor-agnostic; workflow never imports from `anthropic`
  - Convert `LlmMessage_p2p` → `anthropic.types.MessageParam` internally before calling the API
  - Convert `LlmToolDefinition_p2p` → `anthropic.types.ToolParam` internally
  - Initialize `anthropic.AsyncAnthropic` with `ANTHROPIC_API_KEY`; model `claude-sonnet-4-6`
  - Convert the Anthropic response back to `LlmResponse_p2p`: set `block.type` explicitly from `block_obj.type` for each content block; populate the appropriate nested message field (`text`, `tool_use`, or `tool_result`)

#### 2b — `fulfillment-shipping` task queue activities

- [x] `python/fulfillment/src/agents/activities/shipping.py`: `ShippingActivities` class
  - `@activity.defn async def verify_address(request: VerifyAddressRequest_p2p) -> VerifyAddressResponse_p2p`
    - Uses `VerifyAddressRequest_p2p` / `VerifyAddressResponse_p2p` from `generated/pydantic/.../workflows_p2p.py` (already generated; no new proto needed)
    - Call `enablements-api` shipping verification with fields from `request.address`
    - Populate `EasyPostAddress.id`, `.residential`, `.verified`, and `.coordinate` from fixtures
    - Return `VerifyAddressResponse` with `address` carrying fully-populated `easypost_address`
  - `@activity.defn async def get_carrier_rates(request: GetShippingRatesRequest_p2p) -> GetShippingRatesResponse_p2p`
    - Call `enablements-api` shipping rates using `from_easypost_id` + `to_easypost_id` or
      `selected_shipment.easypost.shipment_id`
    - Map fixture rates to `[ShippingOption]` with stable `id`/`rate_id` for LLM cross-referencing
    - Return `GetShippingRatesResponse` with `shipment_id` and `options`
  - No runtime EasyPost API key required

#### 2c — `get_location_events` activity (stubbed)

- `get_location_events` stubbed in `python/fulfillment/src/agents/activities/location_events.py` — returns no events; registered on `agents` task queue; see `specs/fulfillment/location-events/` for planned real implementation

#### 2d — Worker registration

- [x] `python/fulfillment/src/workers/fulfillment_worker.py`: Temporal worker for `fulfillment` task queue
  - Register `ShippingAgent` workflow + `LookupInventoryActivities` + `LlmActivities`
  - No `max_task_queue_activities_per_second` constraint
  - Namespace: `fulfillment`; connect to `TEMPORAL_ADDRESS` env var

- [x] `python/fulfillment/src/workers/shipping_worker.py`: Temporal worker for `fulfillment-shipping` task queue
  - Register `ShippingActivities`
  - Applies a conservative worker-local activity rate guard

- [x] `python/fulfillment/src/worker.py`: entry point (`python -m src.worker`) per Dockerfile — starts `agents` + `fulfillment-shipping` workers concurrently; no separate containers needed

---

### Phase 3 — ShippingAgent Workflow + Agentic Loop
> Blocked on: Phase 2 complete (all activities registered and importable).
> Workflow skeleton (3a) and unit tests (3b) should be developed together.

#### 3a — Workflow implementation

- [x] `python/fulfillment/src/agents/workflows/shipping_agent.py`: `ShippingAgent` workflow skeleton
  - `@workflow.defn(name="ShippingAgent")`
  - State: `_cache: dict[str, ShippingOptionsResult_p2p]`, `_cache_meta: dict[str, datetime]` (stores `cached_at` per hash key)
  - `cache_ttl_secs` stored from `StartShippingAgentRequest.execution_options.cache_ttl_secs`; default `1800` if absent

- [x] `get_options` Query handler
  - `@workflow.query`
  - Return `ShippingOptionsCache_p2p` populated from `_cache`

- [x] `recommend_shipping_option` Update validator
  - `@workflow.update_validator`
  - Assert `request.order_id`, `request.customer_id`, `request.to_address`, and at least one item are present

- [x] Cache key helper `_cache_key(location_id: str, items: list[ShippingLineItem_p2p], postal_code: str, country: str) -> str`
  - Sort items by `sku_id` before hashing
  - SHA-256 over canonical string `f"{location_id}:{sorted_items}:{postal_code}:{country}"` → hex digest

- [x] TTL helper `_is_cache_valid(key: str) -> bool`
  - Return `False` if `key` not in `_cache_meta`
  - Return `False` if `workflow.now() - _cache_meta[key] > timedelta(seconds=cache_ttl_secs)`

- [x] System prompt builder `_build_system_prompt(request: RecommendShippingOptionRequest_p2p) -> str` *(implemented as inline helper — see refactor task below)*
  - Include: margin spike rule from `request.selected_shipment.paid_price`, SLA rule from
    `request.selected_shipment.easypost.selected_rate.delivery_days`, path instruction,
    concurrency instruction, final tool output format

- [ ] **Refactor**: move system prompt computation into a `build_system_prompt` LocalActivity
  - Replace the inline `_build_system_prompt(request)` call in `_run_react_loop` with `await workflow.execute_local_activity("build_system_prompt", args=[request], result_type=str, start_to_close_timeout=timedelta(seconds=5))`
  - Implement `build_system_prompt` as a `@activity.defn` on `LlmActivities` (or a new `ShippingAgentActivities` class) — same logic as the current inline helper, no `ToolSpecs` used
  - Register the activity on the `fulfillment` worker
  - Delete the `_build_system_prompt` module-level function from `shipping_agent.py`
  - **Rationale**: LocalActivity result is memoized in event history; prompt changes do not affect replay of in-flight workflows and do not require a build-id bump

- [x] Tool definitions builder `_build_tool_definitions() -> list[LlmToolDefinition_p2p]`
  - One entry per activity tool: `lookup_inventory_location`, `verify_address`, `get_carrier_rates`, `get_location_events`
  - Each `LlmToolDefinition_p2p`: `name`, `description`, `input_schema` as `dict` (JSON Schema derived from corresponding `_p2p` request type via `model_json_schema()`)
  - Note: `call_llm` is NOT a tool definition — it is the activity that calls Claude, not a tool Claude calls

- [x] `recommend_shipping_option` Update handler — early cache check (fulfillment path)
  - If `request.location_id` and `request.from_address` are both present: compute cache key immediately and return `RecommendShippingOptionResponse(cache_hit=True, ...)` if hit and within TTL; skip LLM entirely

- [x] Agentic loop implementation inside `recommend_shipping_option` handler
  - Build initial messages list from request context (user turn describing the shipping recommendation task)
  - Loop:
    1. Execute `call_llm` activity with `task_queue="fulfillment"`, `schedule_to_close_timeout` from config
    2. If `response.stop_reason == LlmStopReason.LLM_STOP_REASON_TOOL_USE`:
       - Collect all blocks where `block.type == "tool_use"`
       - Dispatch all as concurrent activities using `asyncio.gather` over `workflow.execute_activity()` calls
       - Route per `block.tool_use.name`: `verify_address` / `get_carrier_rates` → `task_queue="fulfillment-shipping"`; `get_location_events` + `lookup_inventory_location` → `task_queue="agents"`
       - Each activity uses `ActivityOptions` with appropriate `start_to_close_timeout`
       - Append all `tool_result` blocks to messages in the same order as `tool_use` blocks
       - If `lookup_inventory_location` was called in this turn: extract `location_id` from its result; compute cache key and check cache (cart path cache check — can only happen after location resolves)
    3. If `response.stop_reason == LlmStopReason.LLM_STOP_REASON_TOOL_USE` and block name is `finalize_recommendation`: extract `ShippingRecommendation` directly from `block.tool_use.input` dict; break loop — **do not dispatch this block as an activity**
    4. If `response.stop_reason == LlmStopReason.LLM_STOP_REASON_END_TURN` with no preceding `finalize_recommendation`: raise retryable `ApplicationError("LLM ended without calling finalize_recommendation")`
  - After loop: store `ShippingOptionsResult` in `_cache[key]`, store `workflow.now()` in `_cache_meta[key]`
  - Return `RecommendShippingOptionResponse(recommendation=..., options=..., cache_hit=False)`;
    options include the de-duped union of rates returned by all primary and alternate
    `get_carrier_rates` calls

- [ ] **Revise**: Replace text-based `ShippingRecommendation` JSON parsing with `finalize_recommendation` tool extraction
  - Add `finalize_recommendation` to the tool list passed to `call_llm` (alongside the four real activity tools); schema: `outcome` enum, `recommended_option_id`, `reasoning`, `margin_delta_cents`, `origin_risk_level` enum, `destination_risk_level` enum — all required
  - In the agentic loop, detect `finalize_recommendation` in `tool_use` blocks before dispatching to `_TOOLS`; extract `ShippingRecommendation` directly from `block.tool_use.input` (already a `dict`; no `json.loads()` needed)
  - Update `build_system_prompt` FINAL RESPONSE instruction: replace "output only raw JSON" with "call the `finalize_recommendation` tool"
  - Remove `_parse_recommendation` function
  - **Rationale**: observed production failures where `claude-haiku-4-5` prefixes its JSON answer with analysis prose or markdown, breaking `json.loads()` non-retryably; tool inputs are always SDK-serialized JSON — prose contamination is structurally impossible

#### 3b — Unit tests

All tests use Temporal Python test framework (`temporalio.testing.WorkflowEnvironment`) with mocked activities.

- [x] `python/fulfillment/tests/test_shipping_agent.py`: cache hit — populate cache before calling Update; assert `call_llm` activity is NOT called; assert `cache_hit=True` in response

- [x] Fulfillment path — provide `from_address` and `location_id` in request; assert `lookup_inventory_location` activity is NOT dispatched across all LLM turns

- [x] Cart path — omit `from_address`; mock `call_llm` to return `lookup_inventory_location` tool call in turn 1; assert `lookup_inventory_location` is dispatched; assert subsequent turns proceed with resolved warehouse address

- [x] Sequential tool dispatch — mock `call_llm` to return single `tool_use` block per turn; assert activities are dispatched one at a time (verify via Temporal test history or call-order assertions)

- [x] Concurrent activity dispatch — mock `call_llm` to return two `tool_use` blocks in one response (e.g., `get_location_events` for origin and destination); assert both activities are dispatched concurrently before either result is appended to messages

- [x] `PROCEED` outcome — mock `call_llm` final turn to call `finalize_recommendation` with `"outcome": "PROCEED"`; assert `RecommendShippingOptionResponse.recommendation.outcome == PROCEED`

- [x] `MARGIN_SPIKE` outcome — mock final `finalize_recommendation` tool input with `"outcome": "MARGIN_SPIKE"` and positive `margin_delta_cents`; assert correct outcome and `margin_delta_cents` propagated

- [x] `SLA_BREACH` outcome — mock final `finalize_recommendation` tool input with `"outcome": "SLA_BREACH"`; assert outcome returned (not raised as error — caller decides what to do)

- [x] TTL expiry triggers re-fetch — seed cache with entry whose `cached_at` is older than `cache_ttl_secs`; assert `call_llm` IS called on subsequent `recommend_shipping_option` Update

---

### Phase 6 — Alternate Warehouse Path Hardening
> Blocked on: nothing (Phase 3 is complete). Can be implemented independently of Phases 4–5.
> Goal: make `find_alternate_warehouse` a workflow-layer guarantee, not a prompt-level suggestion.

#### 6a — Prompt hardening (`llm.py`)

- [x] Change `"RECOMMENDED ACTIONS:"` to `"MANDATORY ACTIONS:"` in `build_system_prompt`
- [x] Rewrite the `find_alternate_warehouse` requirement to explicit mandatory language:
  - "You MUST call `find_alternate_warehouse` before calling `finalize_recommendation` with
    outcome `MARGIN_SPIKE` or `SLA_BREACH`. The system will reject a finalize that arrives
    without this call having been made first."
- [x] Update `find_alternate_warehouse` tool description in `_TOOLS` to match:
  - "You MUST call this before returning MARGIN_SPIKE or SLA_BREACH — a closer warehouse may
    offer cheaper or faster rates. Returns empty address if none available."

#### 6b — Post-loop enforcement (`shipping_agent.py`)

- [x] Add `alternate_warehouse_called: bool = False` at the start of `_run_react_loop`
- [x] In the tool dispatch block, set `alternate_warehouse_called = True` when any dispatched
      block has `block.tool_use.name == "find_alternate_warehouse"`
- [x] In the finalize detection block: when `outcome in ("MARGIN_SPIKE", "SLA_BREACH")` and
      `not alternate_warehouse_called`:
  - Do not break
  - Append a `tool_result` `LlmMessage` for the finalize block's `tool_use.id` with content:
    `{"error": "REJECTED: You must call find_alternate_warehouse before returning MARGIN_SPIKE or SLA_BREACH. Call it now, then re-submit your recommendation."}`
  - Continue the loop
- [x] When `alternate_warehouse_called` is `True`: fall through to `_build_recommendation` and break as normal

#### 6c — Unit tests (`tests/test_shipping_agent.py`)

- [x] `MARGIN_SPIKE` without prior `find_alternate_warehouse` → rejection injected → mock LLM
      responds with `find_alternate_warehouse` call → second finalize accepted
      (`test_margin_spike_enforces_alternate_warehouse`)
- [x] `SLA_BREACH` without prior `find_alternate_warehouse` → same rejection pattern
      (`test_sla_breach_enforces_alternate_warehouse`)
- [x] `MARGIN_SPIKE` with `find_alternate_warehouse` already called → finalize accepted
      immediately (`test_margin_spike_outcome` updated)
- [x] `SLA_BREACH` with `find_alternate_warehouse` already called → finalize accepted
      immediately (`test_sla_breach_outcome` updated)
- [x] `selected_shipment.paid_price.units=1` used in margin spike tests as the deterministic trigger
- [x] Regression coverage ensures options accumulate across primary and alternate rate calls

---

## Revision History

| Date | Author | Change |
|------|--------|--------|
| 2026-04-15 | Temporal FDE Team | Initial stub |
| 2026-04-15 | Temporal FDE Team | Full spec written; all design questions resolved |
| 2026-04-17 | Temporal FDE Team | Approved; all open questions closed; ready for planning |
| 2026-04-17 | Temporal FDE Team | Planning complete; detailed task breakdown added for Phases 1–3 |
| 2026-04-17 | Temporal FDE Team | All 9 planning open questions resolved; task breakdown updated (Q6: removed `VerifyShippingAddressRequest/Response` proto tasks; Q9: removed `continue_as_new` task; Q7: added cross-dependency to fulfillment-order Phase 6) |
| 2026-04-17 | Mike Nichols | Phases 1–3 implemented: proto schema + buf generate, 3 activity classes, 3 worker modules, worker entry point, ShippingAgent workflow with agentic loop, 9 passing unit tests. Notes: (1) temporalio pinned to >=1.26.0 for `@update.validator` API; (2) ShippingOptionLegacy rename in workflows.proto to resolve name conflict; (3) broken unqualified imports fixed in 3 generated _p2p files (codegen bug); (4) system prompt embedded in first user message since call_llm signature is locked to (messages, tools). |
| 2026-04-20 | Mike Nichols | Spec updated: refactor `_build_system_prompt` from inline workflow function to `build_system_prompt` LocalActivity so prompt changes can be shipped without bumping the workflow build-id. Task added to Phase 3 task list. Design Decisions table updated with rationale. |
| 2026-04-25 | Mike Nichols | Spec updated: replace raw JSON text parsing with `finalize_recommendation` forced tool use. Root cause: `claude-haiku-4-5` (and smaller models generally) adds prose/markdown before the JSON despite "output only raw JSON" instructions, causing non-retryable `json.loads()` failures in production. Fix: add a `finalize_recommendation` tool the model must call to submit its answer; tool inputs are SDK-serialized JSON so prose contamination is structurally impossible. Agentic Loop, Design Decisions, and Risks sections updated in spec.md; Phase 3 task revised in PROGRESS.md. |
| 2026-04-27 | Mike Nichols | Phase 6 implemented (11/11 tests passing): Alternate Warehouse Path Hardening. Hardens `find_alternate_warehouse` from a prompt-level suggestion ("RECOMMENDED ACTIONS") into a workflow-layer guarantee via post-loop rejection. The loop tracks `alternate_warehouse_called`; a premature MARGIN_SPIKE/SLA_BREACH finalize is rejected with an explicit error tool_result and the LLM is forced to call the tool. Test trigger: selected shipment paid price of 1 cent deterministically induces MARGIN_SPIKE. Overview, Recommendation Outcomes, Agentic Loop, Activities, Design Decisions, Acceptance Criteria, and Risks sections updated in spec.md. |
| 2026-04-29 | Codex | Updated progress for fixture-backed runtime: shipping activities now call `enablements-api`, task queue/class names are `fulfillment-shipping` and `ShippingActivities`, scenario scripts cover valid/margin/SLA paths, and tests cover accumulated options across alternate warehouse rate calls. |

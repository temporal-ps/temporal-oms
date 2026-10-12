<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { loadGenApi } from '$lib/api/loadgen';
	import type { LoadGeneratorState, ScenarioWeight, BusinessScenarioWeight } from '$lib/api/loadgen';
	import { loadGenSession } from '$lib/stores/loadgen';
	import { deploymentsApi } from '$lib/api/deployments';
	import type { BoundedContext, DeployWorkerVersionResponse } from '$lib/api/deployments';
	import { omsRolloutApi } from '$lib/api/omsRollout';
	import type { OmsVersionRow, OmsVersionRolloutState } from '$lib/api/omsRollout';
	import { runtimeApi } from '$lib/api/runtime';
	import { temporalActivityUrl, temporalWorkerDeploymentUrl } from '$lib/temporalLinks';
	import LoadShapePanel from '$lib/components/LoadShapePanel.svelte';

	const TERMINAL_STATUSES = new Set(['COMPLETED', 'CANCELED', 'FAILED']);
	const ROLLOUT_TERMINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED']);
	const BOUNDED_CONTEXTS: BoundedContext[] = ['apps', 'processing', 'fulfillment'];

	// Runtime: 'unknown' leaves promotion enabled so a failed probe never blocks the demo.
	type Runtime = 'checking' | 'kubernetes' | 'local' | 'unknown';
	let runtime: Runtime = 'checking';

	onMount(async () => {
		try {
			runtime = (await runtimeApi.get()).kubernetes ? 'kubernetes' : 'local';
		} catch (err) {
			runtime = 'unknown';
			console.error(err);
		}
	});

	$: promotionDisabled = runtime === 'local';

	// Load panel: shapes map to commerce/payment webhook scenarios plus business scenarios.
	const SHAPES: Record<string, { scenarioWeights: ScenarioWeight[]; businessScenarioWeights: BusinessScenarioWeight[] }> = {
		clean: {
			scenarioWeights: [{ scenario: 'NORMAL', weight: 100 }],
			businessScenarioWeights: [{ scenario: 'BUSINESS_SCENARIO_NORMAL', weight: 100 }]
		},
		mixed: {
			scenarioWeights: [
				{ scenario: 'NORMAL', weight: 80 },
				{ scenario: 'PAYMENT_BEFORE_COMMERCE', weight: 10 },
				{ scenario: 'MISSING_COMMERCE_EVENT', weight: 5 },
				{ scenario: 'MISSING_PAYMENT_EVENT', weight: 5 }
			],
			businessScenarioWeights: [
				{ scenario: 'BUSINESS_SCENARIO_NORMAL', weight: 85 },
				{ scenario: 'BUSINESS_SCENARIO_MARGIN_SPIKE', weight: 5 },
				{ scenario: 'BUSINESS_SCENARIO_SLA_BREACH', weight: 5 },
				{ scenario: 'BUSINESS_SCENARIO_INVALID_ORDER', weight: 5 }
			]
		},
		chaos: {
			scenarioWeights: [
				{ scenario: 'NORMAL', weight: 40 },
				{ scenario: 'PAYMENT_BEFORE_COMMERCE', weight: 20 },
				{ scenario: 'MISSING_COMMERCE_EVENT', weight: 20 },
				{ scenario: 'MISSING_PAYMENT_EVENT', weight: 20 }
			],
			businessScenarioWeights: [
				{ scenario: 'BUSINESS_SCENARIO_NORMAL', weight: 40 },
				{ scenario: 'BUSINESS_SCENARIO_MARGIN_SPIKE', weight: 20 },
				{ scenario: 'BUSINESS_SCENARIO_SLA_BREACH', weight: 20 },
				{ scenario: 'BUSINESS_SCENARIO_INVALID_ORDER', weight: 20 }
			]
		}
	};

	const RATES: Record<string, number> = { trickle: 6, steady: 12, burst: 60 };
	const ORDER_COUNT = 5000;
	const LOAD_TIMEOUT = '3600s';

	let loadState: LoadGeneratorState | null = null;
	let ratePerMinute = 0;
	let loadBusy = false;
	let loadError = '';
	let pollHandle: ReturnType<typeof setInterval> | undefined;

	function startPolling(enablementId: string) {
		stopPolling();
		pollHandle = setInterval(() => poll(enablementId), 2000);
		poll(enablementId);
	}

	function stopPolling() {
		if (pollHandle) {
			clearInterval(pollHandle);
			pollHandle = undefined;
		}
	}

	async function poll(enablementId: string) {
		try {
			loadState = await loadGenApi.getState(enablementId);
			if (TERMINAL_STATUSES.has(loadState.status)) {
				stopPolling();
				loadGenSession.clear();
				loadState = null;
			}
		} catch {
			stopPolling();
			loadGenSession.clear();
			loadState = null;
		}
	}

	async function handleStartLoad(shape: string, rate: string) {
		loadBusy = true;
		loadError = '';
		try {
			const picked = SHAPES[shape];
			ratePerMinute = RATES[rate];
			const result = await loadGenApi.start({
				orderCount: ORDER_COUNT,
				submitRatePerMin: ratePerMinute,
				timeout: LOAD_TIMEOUT,
				scenarioWeights: picked.scenarioWeights,
				businessScenarioWeights: picked.businessScenarioWeights
			});
			loadGenSession.set(result.enablementId);
			startPolling(result.enablementId);
		} catch (err) {
			loadError = 'Failed to start the load generator.';
			console.error(err);
		} finally {
			loadBusy = false;
		}
	}

	async function handleStopLoad() {
		if (!$loadGenSession) return;
		loadBusy = true;
		try {
			await loadGenApi.stop($loadGenSession.enablementId, 'Stopped from Admin');
			stopPolling();
			loadGenSession.clear();
			loadState = null;
		} catch (err) {
			loadError = 'Failed to stop the load generator.';
			console.error(err);
		} finally {
			loadBusy = false;
		}
	}

	if ($loadGenSession) {
		startPolling($loadGenSession.enablementId);
	}

	$: loadTemporalUrl = $loadGenSession ? temporalActivityUrl('default', $loadGenSession.enablementId) : '';
	// Referencing loadState keeps this recomputing on each poll tick.
	$: elapsedSeconds =
		$loadGenSession && loadState ? Math.floor((Date.now() - $loadGenSession.startedAt) / 1000) : 0;

	// OMS version panel: one OmsVersionRollout workflow promotes apps, processing, and
	// fulfillment in the order that avoids an unsafe intermediate combination.
	let omsRows: OmsVersionRow[] = [];
	let selectedOmsVersion = '';
	let rolloutState: OmsVersionRolloutState | null = null;
	let rolloutBusy = false;
	let rolloutError = '';
	let rolloutPollHandle: ReturnType<typeof setInterval> | undefined;

	onMount(loadOmsVersions);

	async function loadOmsVersions() {
		try {
			const { rows } = await omsRolloutApi.listVersions();
			omsRows = rows;
		} catch (err) {
			rolloutError = 'Failed to load the OMS version list.';
			console.error(err);
		}
	}

	function startRolloutPolling(rolloutId: string) {
		stopRolloutPolling();
		rolloutPollHandle = setInterval(() => pollRollout(rolloutId), 2000);
		pollRollout(rolloutId);
	}

	function stopRolloutPolling() {
		if (rolloutPollHandle) {
			clearInterval(rolloutPollHandle);
			rolloutPollHandle = undefined;
		}
	}

	async function pollRollout(rolloutId: string) {
		try {
			rolloutState = await omsRolloutApi.getState(rolloutId);
			if (ROLLOUT_TERMINAL_STATUSES.has(rolloutState.overallStatus)) {
				stopRolloutPolling();
			}
		} catch (err) {
			stopRolloutPolling();
			rolloutError = 'Lost track of the rollout.';
			console.error(err);
		}
	}

	async function handleStartRollout() {
		if (!selectedOmsVersion) {
			rolloutError = 'Choose an OMS version.';
			return;
		}
		rolloutBusy = true;
		rolloutError = '';
		try {
			rolloutState = await omsRolloutApi.start(selectedOmsVersion);
			startRolloutPolling(rolloutState.rolloutId);
		} catch (err) {
			rolloutError = `Failed to start rollout: ${err instanceof Error ? err.message : String(err)}`;
			console.error(err);
		} finally {
			rolloutBusy = false;
		}
	}

	onDestroy(() => {
		stopPolling();
		stopRolloutPolling();
	});

	// Advanced panel: one row per bounded context, independent of the OMS version panel.
	interface ContextRow {
		context: BoundedContext;
		version: string;
		busy: boolean;
		error: string;
		lastResult: DeployWorkerVersionResponse | null;
		lastPromotedAt: number | null;
	}

	let rows: Record<BoundedContext, ContextRow> = Object.fromEntries(
		BOUNDED_CONTEXTS.map((context) => [
			context,
			{ context, version: '', busy: false, error: '', lastResult: null, lastPromotedAt: null }
		])
	) as Record<BoundedContext, ContextRow>;

	async function promote(context: BoundedContext) {
		const row = rows[context];
		if (!row.version.trim()) {
			row.error = 'Enter a target version (e.g. v3).';
			rows = { ...rows };
			return;
		}
		row.busy = true;
		row.error = '';
		rows = { ...rows };
		try {
			const result = await deploymentsApi.promote(context, { version: row.version.trim() });
			row.lastResult = result;
			row.lastPromotedAt = Date.now();
			if (!result.currentVersionSet) {
				row.error = 'set-current-version did not confirm; check describe output below.';
			}
		} catch (err) {
			row.error = `Promotion failed: ${err instanceof Error ? err.message : String(err)}`;
			console.error(err);
		} finally {
			row.busy = false;
			rows = { ...rows };
		}
	}

	const RUNTIME_LABELS: Record<Runtime, string> = {
		checking: 'Checking runtime…',
		kubernetes: 'Running on Kubernetes (k3d/KinD)',
		local: 'Running as local processes',
		unknown: 'Runtime unknown'
	};
</script>

<svelte:head>
	<title>Admin - Temporal OMS</title>
</svelte:head>

<div class="max-w-4xl mx-auto px-4 py-12 sm:px-6 lg:px-8">
	<div class="flex flex-wrap items-center justify-between gap-4 mb-2">
		<h1 class="text-3xl font-bold text-gray-900">Admin</h1>
		<span
			class="text-xs font-medium px-3 py-1 rounded-full {runtime === 'kubernetes'
				? 'bg-green-100 text-green-800'
				: runtime === 'local'
					? 'bg-blue-100 text-blue-800'
					: 'bg-gray-100 text-gray-700'}"
		>
			{RUNTIME_LABELS[runtime]}
		</span>
	</div>
	<p class="text-gray-600 mb-8">
		Drive order load and roll out worker versions. Load and deployment are independent: load
		never needs a deployment, and a promotion never needs load.
	</p>

	<section class="mb-10">
		{#if loadError}
			<div class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-800">{loadError}</div>
		{/if}

		<LoadShapePanel
			state={loadState}
			{ratePerMinute}
			busy={loadBusy}
			onStart={handleStartLoad}
			onStop={handleStopLoad}
		/>

		{#if $loadGenSession}
			<div class="mt-4 flex items-center justify-between text-sm text-gray-500">
				<span>Elapsed: {elapsedSeconds}s · Enablement <span class="font-mono">{$loadGenSession.enablementId}</span></span>
				<a href={loadTemporalUrl} target="_blank" class="text-primary-600 hover:text-primary-700">
					View in Temporal UI →
				</a>
			</div>
		{/if}
	</section>

	<h2 class="text-2xl font-bold text-gray-900 mb-4">Worker Versions</h2>

	{#if promotionDisabled}
		<div class="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-900">
			<p class="font-medium">Worker version promotion needs Kubernetes.</p>
			<p class="mt-1">
				Each promotion creates and deletes Kubernetes Deployments, so the controls below are
				disabled while the OMS runs as local processes. To try it, stop this stack and start the
				k3d demo:
			</p>
			<pre class="mt-2 bg-white border border-blue-100 rounded p-2 text-xs">./scripts/local-down.sh
./scripts/k3d/demo-up.sh</pre>
			<p class="mt-2">See docs/GETTING_STARTED.md, "Demo 2: Worker version rollout".</p>
		</div>
	{/if}

	<fieldset disabled={promotionDisabled} class={promotionDisabled ? 'opacity-50' : ''}>
		<section class="mb-10 p-6 border border-gray-200 rounded-lg">
			<h3 class="text-xl font-semibold text-gray-900 mb-4">OMS Version</h3>
			<p class="text-sm text-gray-500 mb-6">
				Pick a target OMS version and promote apps, processing, and fulfillment together, in the
				order that avoids an unsafe intermediate combination. Steps whose target is "embedded" are
				skipped, not attempted.
			</p>

			{#if rolloutError}
				<div class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-800">{rolloutError}</div>
			{/if}

			<div class="flex flex-wrap items-center gap-2 mb-4">
				<select
					class="border border-gray-300 rounded px-2 py-1 text-sm max-w-full"
					bind:value={selectedOmsVersion}
					disabled={rolloutBusy || omsRows.length === 0}
				>
					<option value="">Select an OMS version…</option>
					{#each omsRows as row}
						<option value={row.omsVersion}>
							{row.omsVersion} (apps {row.appsVersion}, processing {row.processingVersion}, fulfillment {row.fulfillmentVersion}){row.future ? ', future' : ''}
						</option>
					{/each}
				</select>
				<button
					class="px-4 py-2 bg-primary-600 text-white rounded disabled:opacity-50"
					disabled={rolloutBusy || !selectedOmsVersion}
					on:click={handleStartRollout}
				>
					{rolloutBusy ? 'Starting…' : 'Start rollout'}
				</button>
			</div>

			{#if rolloutState}
				<div class="text-sm text-gray-700 mb-2">
					Rollout <span class="font-mono">{rolloutState.rolloutId}</span> to
					<span class="font-medium">{rolloutState.omsVersion}</span>: {rolloutState.overallStatus}
				</div>
				{#if rolloutState.errorMessage}
					<div class="mb-2 p-2 bg-red-50 border border-red-200 rounded text-sm text-red-800">
						{rolloutState.errorMessage}
					</div>
				{/if}
				<div class="space-y-2">
					{#each rolloutState.steps as step}
						<div class="flex items-center justify-between border border-gray-100 rounded px-3 py-2">
							<div class="text-sm">
								<span class="font-medium capitalize">{step.boundedContext}</span>
								<span class="text-gray-500">→ {step.targetVersion}</span>
								<span class="ml-2 text-xs uppercase text-gray-500">{step.status}</span>
								{#if step.errorMessage}
									<div class="text-xs text-red-700">{step.errorMessage}</div>
								{/if}
							</div>
							<a
								href={temporalWorkerDeploymentUrl(step.boundedContext, step.boundedContext)}
								target="_blank"
								class="text-primary-600 hover:text-primary-700 text-xs"
							>
								View in Temporal UI →
							</a>
						</div>
					{/each}
				</div>
			{/if}
		</section>

		<section class="p-6 border border-gray-200 rounded-lg">
			<h3 class="text-xl font-semibold text-gray-900 mb-4">Advanced: promote a single bounded context</h3>
			<p class="text-sm text-gray-500 mb-6">
				Use this to demonstrate an unsafe pairing on purpose, or to recover manually if an OMS
				version rollout fails partway. The Temporal UI link on each row shows the authoritative
				current build id. This page shows only the result of the last promotion started here.
			</p>

			<div class="space-y-6">
				{#each BOUNDED_CONTEXTS as context}
					{@const row = rows[context]}
					<div class="border-t border-gray-100 pt-4 first:border-t-0 first:pt-0">
						<div class="flex items-center justify-between mb-2">
							<h4 class="font-medium text-gray-900 capitalize">{context}</h4>
							<a
								href={temporalWorkerDeploymentUrl(context, context)}
								target="_blank"
								class="text-primary-600 hover:text-primary-700 text-sm"
							>
								View in Temporal UI →
							</a>
						</div>

						<div class="flex items-center gap-2 mb-2">
							<input
								class="border border-gray-300 rounded px-2 py-1 text-sm w-32"
								placeholder="e.g. v3"
								bind:value={row.version}
								disabled={row.busy}
							/>
							<button
								class="px-3 py-1 bg-primary-600 text-white rounded text-sm disabled:opacity-50"
								disabled={row.busy}
								on:click={() => promote(context)}
							>
								{row.busy ? 'Promoting…' : 'Promote'}
							</button>
						</div>

						{#if row.error}
							<p class="text-sm text-red-700 mb-2">{row.error}</p>
						{/if}

						{#if row.lastResult}
							<div class="text-xs text-gray-600 bg-gray-50 rounded p-2">
								<div>Workflow class: <span class="font-mono">{row.lastResult.workflowClass}</span></div>
								<div>Current version set: {row.lastResult.currentVersionSet ? 'yes' : 'no'}</div>
								{#if row.lastPromotedAt}
									<div>At: {new Date(row.lastPromotedAt).toLocaleTimeString()}</div>
								{/if}
								{#if row.lastResult.describeOutput}
									<details class="mt-1">
										<summary class="cursor-pointer">describe output</summary>
										<pre class="whitespace-pre-wrap break-all">{row.lastResult.describeOutput}</pre>
									</details>
								{/if}
							</div>
						{/if}
					</div>
				{/each}
			</div>
		</section>
	</fieldset>
</div>

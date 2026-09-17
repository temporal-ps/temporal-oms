<script lang="ts">
	import { onDestroy } from 'svelte';
	import { loadGenApi } from '$lib/api/loadgen';
	import type { LoadGeneratorState } from '$lib/api/loadgen';
	import { loadGenSession } from '$lib/stores/loadgen';
	import { deploymentsApi } from '$lib/api/deployments';
	import type { BoundedContext, DeployWorkerVersionResponse } from '$lib/api/deployments';
	import { temporalActivityUrl, temporalWorkerDeploymentUrl } from '$lib/temporalLinks';

	const TERMINAL_STATUSES = new Set(['COMPLETED', 'CANCELED', 'FAILED']);
	const BOUNDED_CONTEXTS: BoundedContext[] = ['apps', 'processing', 'fulfillment'];

	// -- Load panel: reuses the existing /api/v1/enablements/worker-version endpoints
	// and session store as-is (hosting.md Mode B: load and deploy are independent).
	let loadState: LoadGeneratorState | null = null;
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

	async function handleStartLoad() {
		loadBusy = true;
		loadError = '';
		try {
			const result = await loadGenApi.start({
				orderCount: 5000,
				submitRatePerMin: 12,
				timeout: '3600s',
				scenarioWeights: [{ scenario: 'NORMAL', weight: 100 }],
				businessScenarioWeights: [{ scenario: 'BUSINESS_SCENARIO_NORMAL', weight: 100 }]
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

	onDestroy(stopPolling);

	$: loadTemporalUrl = $loadGenSession ? temporalActivityUrl('default', $loadGenSession.enablementId) : '';

	// -- Deployment panel: one row per bounded context, independent of the load panel above.
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
</script>

<svelte:head>
	<title>Worker Versions - Admin</title>
</svelte:head>

<div class="max-w-4xl mx-auto px-4 py-12 sm:px-6 lg:px-8">
	<h1 class="text-3xl font-bold text-gray-900 mb-2">Worker Version Control</h1>
	<p class="text-gray-600 mb-8">
		Operator page for on-demand rollout (hosting.md Mode B). Load and deployment are independent:
		promoting a bounded context never requires load to be running, and load never requires a
		deployment to have happened.
	</p>

	<section class="mb-10 p-6 border border-gray-200 rounded-lg">
		<h2 class="text-xl font-semibold text-gray-900 mb-4">Load</h2>

		{#if loadError}
			<div class="mb-4 p-3 bg-red-50 border border-red-200 rounded text-sm text-red-800">{loadError}</div>
		{/if}

		{#if $loadGenSession && loadState}
			<div class="flex items-center justify-between mb-4">
				<div class="text-sm text-gray-700">
					<div>Enablement: <span class="font-mono">{$loadGenSession.enablementId}</span></div>
					<div>Status: {loadState.status} · Orders submitted: {loadState.ordersSubmittedCount}</div>
				</div>
				<a href={loadTemporalUrl} target="_blank" class="text-primary-600 hover:text-primary-700 text-sm">
					View in Temporal UI →
				</a>
			</div>
			<button
				class="px-4 py-2 bg-gray-800 text-white rounded disabled:opacity-50"
				disabled={loadBusy}
				on:click={handleStopLoad}
			>
				Stop load
			</button>
		{:else}
			<p class="text-sm text-gray-500 mb-4">No load generator running.</p>
			<button
				class="px-4 py-2 bg-primary-600 text-white rounded disabled:opacity-50"
				disabled={loadBusy}
				on:click={handleStartLoad}
			>
				Start load
			</button>
		{/if}
	</section>

	<section class="p-6 border border-gray-200 rounded-lg">
		<h2 class="text-xl font-semibold text-gray-900 mb-4">Deployments</h2>
		<p class="text-sm text-gray-500 mb-6">
			Current build-id is authoritative in the Temporal UI, not re-derived here: use the link per
			row to confirm. This page shows only the result of the last promotion triggered from here.
		</p>

		<div class="space-y-6">
			{#each BOUNDED_CONTEXTS as context}
				{@const row = rows[context]}
				<div class="border-t border-gray-100 pt-4 first:border-t-0 first:pt-0">
					<div class="flex items-center justify-between mb-2">
						<h3 class="font-medium text-gray-900 capitalize">{context}</h3>
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
</div>

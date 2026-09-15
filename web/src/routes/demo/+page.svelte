<script lang="ts">
	import { onDestroy } from 'svelte';
	import { loadGenApi } from '$lib/api/loadgen';
	import type { LoadGeneratorState, ScenarioWeight, BusinessScenarioWeight } from '$lib/api/loadgen';
	import { loadGenSession } from '$lib/stores/loadgen';
	import { temporalWorkflowUrl } from '$lib/temporalLinks';
	import LoadShapePanel from '$lib/components/LoadShapePanel.svelte';

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
	const TIMEOUT = '3600s';

	let state: LoadGeneratorState | null = null;
	let paused = false;
	let busy = false;
	let error = '';
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
			state = await loadGenApi.getState(enablementId);
			if (state.currentPhase === 'COMPLETE') {
				stopPolling();
				loadGenSession.clear();
				state = null;
			}
		} catch (err) {
			stopPolling();
			loadGenSession.clear();
			state = null;
			paused = false;
		}
	}

	async function handleStart(shape: string, rate: string) {
		busy = true;
		error = '';
		try {
			const picked = SHAPES[shape];
			const result = await loadGenApi.start({
				orderCount: ORDER_COUNT,
				submitRatePerMin: RATES[rate],
				timeout: TIMEOUT,
				scenarioWeights: picked.scenarioWeights,
				businessScenarioWeights: picked.businessScenarioWeights
			});
			loadGenSession.set(result.enablementId);
			paused = false;
			startPolling(result.enablementId);
		} catch (err) {
			error = 'Failed to start the load generator. Please try again.';
			console.error(err);
		} finally {
			busy = false;
		}
	}

	async function handlePause() {
		if (!$loadGenSession) return;
		busy = true;
		try {
			await loadGenApi.pause($loadGenSession.enablementId);
			paused = true;
		} catch (err) {
			error = 'Failed to pause the load generator.';
			console.error(err);
		} finally {
			busy = false;
		}
	}

	async function handleResume() {
		if (!$loadGenSession) return;
		busy = true;
		try {
			await loadGenApi.resume($loadGenSession.enablementId);
			paused = false;
		} catch (err) {
			error = 'Failed to resume the load generator.';
			console.error(err);
		} finally {
			busy = false;
		}
	}

	async function handleStop() {
		if (!$loadGenSession) return;
		busy = true;
		try {
			await loadGenApi.stop($loadGenSession.enablementId, 'Stopped from UI');
			stopPolling();
			loadGenSession.clear();
			state = null;
			paused = false;
		} catch (err) {
			error = 'Failed to stop the load generator.';
			console.error(err);
		} finally {
			busy = false;
		}
	}

	if ($loadGenSession) {
		startPolling($loadGenSession.enablementId);
	}

	onDestroy(stopPolling);

	$: temporalUrl = $loadGenSession ? temporalWorkflowUrl('default', $loadGenSession.enablementId) : '';
	// Referencing `state` keeps this recomputing on each poll tick, not just on session start/stop.
	$: elapsedSeconds = $loadGenSession && state ? Math.floor((Date.now() - $loadGenSession.startedAt) / 1000) : 0;
</script>

<svelte:head>
	<title>Demo Load Control - Temporal OMS</title>
</svelte:head>

<div class="max-w-2xl mx-auto px-4 py-12 sm:px-6 lg:px-8">
	<h1 class="text-3xl font-bold text-gray-900 mb-2">Demo Load Control</h1>
	<p class="text-gray-600 mb-8">
		Drive a stream of order traffic through the OMS to exercise the safe-deploys / worker-versioning
		workshop live.
	</p>

	{#if error}
		<div class="mb-6 p-4 bg-red-50 border border-red-200 rounded-lg">
			<p class="text-sm text-red-800">{error}</p>
		</div>
	{/if}

	<LoadShapePanel
		{state}
		{paused}
		{busy}
		onStart={handleStart}
		onPause={handlePause}
		onResume={handleResume}
		onStop={handleStop}
	/>

	{#if $loadGenSession}
		<div class="mt-6 flex items-center justify-between text-sm text-gray-500">
			<span>Elapsed: {elapsedSeconds}s</span>
			<a href={temporalUrl} target="_blank" class="text-primary-600 hover:text-primary-700">
				View in Temporal UI →
			</a>
		</div>
	{/if}
</div>

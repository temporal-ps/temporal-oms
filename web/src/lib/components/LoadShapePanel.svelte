<script lang="ts">
	import type { LoadGeneratorState } from '$lib/api/loadgen';

	export let state: LoadGeneratorState | null;
	export let paused = false;
	export let busy = false;
	export let onStart: (shape: string, rate: string) => void;
	export let onPause: () => void;
	export let onResume: () => void;
	export let onStop: () => void;

	let shape: 'clean' | 'mixed' | 'chaos' = 'mixed';
	let rate: 'trickle' | 'steady' | 'burst' = 'steady';

	const SHAPE_LABELS: Record<string, string> = {
		clean: 'Clean — all orders complete normally',
		mixed: 'Mixed — a realistic sprinkle of webhook/business edge cases',
		chaos: 'Chaos — heavy mix of edge cases and business exceptions'
	};

	const RATE_LABELS: Record<string, string> = {
		trickle: 'Trickle — 6 orders/min',
		steady: 'Steady — 12 orders/min',
		burst: 'Burst — 60 orders/min'
	};

	$: running = state !== null;
</script>

<div class="bg-white rounded-lg shadow-md p-6">
	<h2 class="text-xl font-semibold text-gray-900 mb-4">Load Generator</h2>

	{#if !running}
		<div class="space-y-4">
			<div>
				<h3 class="text-sm font-semibold text-gray-900 mb-2">Shape</h3>
				<select bind:value={shape} class="w-full text-sm border border-gray-300 rounded-lg px-3 py-2">
					{#each Object.entries(SHAPE_LABELS) as [value, label]}
						<option {value}>{label}</option>
					{/each}
				</select>
			</div>

			<div>
				<h3 class="text-sm font-semibold text-gray-900 mb-2">Rate</h3>
				<select bind:value={rate} class="w-full text-sm border border-gray-300 rounded-lg px-3 py-2">
					{#each Object.entries(RATE_LABELS) as [value, label]}
						<option {value}>{label}</option>
					{/each}
				</select>
			</div>

			<button
				onclick={() => onStart(shape, rate)}
				disabled={busy}
				class="w-full bg-primary-600 text-white px-6 py-3 rounded-lg font-semibold hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
			>
				{busy ? 'Starting...' : 'Start load'}
			</button>
		</div>
	{:else}
		<div class="space-y-4">
			<div class="grid grid-cols-3 gap-4 text-center">
				<div>
					<div class="text-2xl font-bold text-gray-900">{state?.ordersSubmittedCount ?? 0}</div>
					<div class="text-xs text-gray-500">Orders submitted</div>
				</div>
				<div>
					<div class="text-2xl font-bold text-gray-900">{state?.ordersPerMinute ?? 0}</div>
					<div class="text-xs text-gray-500">Orders / min</div>
				</div>
				<div>
					<div class="text-2xl font-bold text-gray-900">{state?.currentPhase ?? '—'}</div>
					<div class="text-xs text-gray-500">Phase</div>
				</div>
			</div>

			<div class="flex gap-3">
				{#if paused}
					<button
						onclick={onResume}
						disabled={busy}
						class="flex-1 bg-primary-600 text-white px-6 py-3 rounded-lg font-semibold hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
					>
						Resume
					</button>
				{:else}
					<button
						onclick={onPause}
						disabled={busy}
						class="flex-1 bg-gray-200 text-gray-900 px-6 py-3 rounded-lg font-semibold hover:bg-gray-300 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
					>
						Pause
					</button>
				{/if}
				<button
					onclick={onStop}
					disabled={busy}
					class="flex-1 bg-red-600 text-white px-6 py-3 rounded-lg font-semibold hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
				>
					Stop
				</button>
			</div>
		</div>
	{/if}
</div>

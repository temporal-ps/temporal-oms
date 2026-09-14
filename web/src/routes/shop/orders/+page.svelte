<script lang="ts">
	import type { PageData } from './$types';
	import { orderTrackingLinks } from '$lib/temporalLinks';
	import { api } from '$lib/api/client';
	import { customerId } from '$lib/stores/customer';

	export let data: PageData;

	$: trackingLinks = data.order
		? orderTrackingLinks(data.order.orderId, data.charge?.chargeId ?? null)
		: [];

	let cancelling = false;
	let cancelError = '';
	let cancelled = false;

	async function handleCancelOrder() {
		if (!data.order || cancelling) return;
		cancelling = true;
		cancelError = '';
		try {
			await api.cancelOrder(data.order.orderId, 'Customer requested cancellation', $customerId);
			cancelled = true;
		} catch (err) {
			cancelError = 'Failed to cancel order. It may have already completed.';
			console.error(err);
		} finally {
			cancelling = false;
		}
	}

	function formatPrice(priceCents: number): string {
		return `$${(priceCents / 100).toFixed(2)}`;
	}

	function getStatusColor(status: string): string {
		const colors: Record<string, string> = {
			PLACED: 'bg-blue-100 text-blue-800',
			AUTHORIZED: 'bg-yellow-100 text-yellow-800',
			CAPTURED: 'bg-green-100 text-green-800',
			VOIDED: 'bg-gray-100 text-gray-800',
			CAPTURE_FAILED: 'bg-red-100 text-red-800',
			DECLINED: 'bg-red-100 text-red-800',
			INSUFFICIENT_FUNDS: 'bg-red-100 text-red-800',
			EXPIRED_CARD: 'bg-red-100 text-red-800'
		};
		return colors[status] || 'bg-gray-100 text-gray-800';
	}
</script>

<svelte:head>
	<title>My Orders - Temporal OMS</title>
</svelte:head>

<div class="max-w-4xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
	<div class="mb-8">
		<h1 class="text-3xl font-bold text-gray-900">My Orders</h1>
		<p class="mt-2 text-gray-600">Customer ID: {data.customerId}</p>
	</div>

	{#if !data.order}
		<div class="bg-white rounded-lg shadow-md p-12 text-center">
			<h2 class="text-xl font-semibold text-gray-900 mb-2">No orders yet</h2>
			<p class="text-gray-600 mb-6">Start shopping to see your order here</p>
			<a
				href="/shop/home"
				class="inline-flex items-center bg-primary-600 text-white px-6 py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors"
			>
				Start Shopping
			</a>
		</div>
	{:else}
		<div class="bg-white rounded-lg shadow-md overflow-hidden">
			<div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
				<div class="flex flex-wrap items-center justify-between gap-4">
					<div>
						<h3 class="text-lg font-semibold text-gray-900">Order #{data.order.orderId}</h3>
						<span class="px-3 py-1 rounded-full text-sm font-medium {getStatusColor(data.order.status)}">
							{data.order.status}
						</span>
					</div>
					<button
						onclick={handleCancelOrder}
						disabled={cancelling || cancelled}
						class="text-sm font-medium text-red-600 hover:text-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
					>
						{cancelled ? 'Cancellation requested' : cancelling ? 'Cancelling...' : 'Cancel Order'}
					</button>
				</div>
			</div>
			{#if cancelError}
				<div class="px-6 py-3 bg-red-50 border-t border-red-200">
					<p class="text-sm text-red-800">{cancelError}</p>
				</div>
			{/if}
			<div class="px-6 py-4">
				<div class="space-y-3">
					{#each data.order.items as item}
						<div class="flex items-center justify-between">
							<p class="text-gray-900">{item.itemId}</p>
							<p class="text-sm text-gray-600">Quantity: {item.quantity}</p>
						</div>
					{/each}
				</div>
			</div>
		</div>

		{#if data.charge}
			<div class="mt-6 bg-white rounded-lg shadow-md overflow-hidden">
				<div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
					<h3 class="text-lg font-semibold text-gray-900">Payment #{data.charge.chargeId}</h3>
					<span class="px-3 py-1 rounded-full text-sm font-medium {getStatusColor(data.charge.status)}">
						{data.charge.status}
					</span>
				</div>
				{#if data.charge.declineReason}
					<div class="px-6 py-4">
						<p class="text-sm text-gray-600">Reason: {data.charge.declineReason}</p>
					</div>
				{/if}
			</div>
		{/if}

		<div class="mt-6 bg-white rounded-lg shadow-md overflow-hidden">
			<div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
				<h3 class="text-lg font-semibold text-gray-900">Track this order</h3>
				<p class="text-sm text-gray-600">
					The order ID is the Temporal workflow ID at every stage. Follow it through each
					namespace, in order, to see the full submission-to-fulfillment history.
				</p>
			</div>
			<ul class="divide-y divide-gray-200">
				{#each trackingLinks as link}
					<li class="px-6 py-3 flex items-center justify-between">
						<div>
							<p class="text-gray-900">{link.label}</p>
							<p class="text-sm text-gray-500">namespace: {link.namespace} · workflow: {link.workflowId}</p>
						</div>
						<a
							href={link.url}
							target="_blank"
							rel="noreferrer"
							class="text-primary-600 hover:text-primary-700 font-medium text-sm"
						>
							View in Temporal UI
						</a>
					</li>
				{/each}
			</ul>
		</div>
	{/if}
</div>

<script lang="ts">
	import { goto } from '$app/navigation';
	import { customerId } from '$lib/stores/customer';
	import { cart } from '$lib/stores/cart';
	import { orderId } from '$lib/stores/order';
	import { chargeId } from '$lib/stores/charge';

	let customerIdInput = '';
	let error = '';

	function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		if (!customerIdInput.trim()) {
			error = 'Customer ID is required';
			return;
		}

		// Clear previous session data; the order/charge ID are assigned
		// server-side once checkout actually places an order.
		cart.clear();
		orderId.clear();
		chargeId.clear();
		customerId.set(customerIdInput.trim());

		// Redirect to clothing page
		goto('/shop/clothing');
	}
</script>

<svelte:head>
	<title>Shop - Temporal OMS</title>
</svelte:head>

<div class="max-w-2xl mx-auto px-4 py-16 sm:px-6 lg:px-8">
	<div class="bg-white rounded-lg shadow-lg p-8">
		<div class="text-center mb-8">
			<div class="inline-flex items-center justify-center w-20 h-20 bg-primary-100 rounded-full mb-4">
				<svg
					class="w-10 h-10 text-primary-600"
					fill="none"
					stroke="currentColor"
					viewBox="0 0 24 24"
				>
					<path
						stroke-linecap="round"
						stroke-linejoin="round"
						stroke-width="2"
						d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z"
					/>
				</svg>
			</div>
			<h1 class="text-3xl font-bold text-gray-900">Welcome to Our Store</h1>
			<p class="mt-2 text-gray-600">Enter your customer ID to start shopping</p>
		</div>

		<form onsubmit={handleSubmit} class="space-y-6">
			<div>
				<label for="customerId" class="block text-sm font-medium text-gray-700 mb-2">
					Customer ID
				</label>
				<input
					type="text"
					id="customerId"
					bind:value={customerIdInput}
					placeholder="e.g., customer-123"
					class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent transition-shadow"
					required
				/>
				{#if error}
					<p class="mt-2 text-sm text-red-600">{error}</p>
				{/if}
			</div>

			<button
				type="submit"
				class="w-full bg-primary-600 text-white px-6 py-4 rounded-lg font-semibold text-lg hover:bg-primary-700 focus:ring-4 focus:ring-primary-200 transition-all"
			>
				Shop Clothing
			</button>
		</form>

		<div class="mt-8 pt-8 border-t border-gray-200">
			<h3 class="text-sm font-semibold text-gray-900 mb-3">How it works:</h3>
			<ol class="space-y-2 text-sm text-gray-600">
				<li class="flex items-start">
					<span class="flex-shrink-0 w-6 h-6 bg-primary-100 rounded-full flex items-center justify-center text-primary-600 font-semibold mr-3">
						1
					</span>
					<span>Enter your customer ID to start a new session</span>
				</li>
				<li class="flex items-start">
					<span class="flex-shrink-0 w-6 h-6 bg-primary-100 rounded-full flex items-center justify-center text-primary-600 font-semibold mr-3">
						2
					</span>
					<span>Browse our clothing catalog and add items to your cart</span>
				</li>
				<li class="flex items-start">
					<span class="flex-shrink-0 w-6 h-6 bg-primary-100 rounded-full flex items-center justify-center text-primary-600 font-semibold mr-3">
						3
					</span>
					<span>Enter shipping details and complete payment with a test card number</span>
				</li>
				<li class="flex items-start">
					<span class="flex-shrink-0 w-6 h-6 bg-primary-100 rounded-full flex items-center justify-center text-primary-600 font-semibold mr-3">
						4
					</span>
					<span>Track your order status in real-time</span>
				</li>
			</ol>
		</div>
	</div>
</div>

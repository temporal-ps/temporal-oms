<script lang="ts">
	import { goto } from '$app/navigation';
	import { cart, cartTotal } from '$lib/stores/cart';
	import { customerId } from '$lib/stores/customer';
	import { orderId as currentOrderId } from '$lib/stores/order';
	import { chargeId as currentChargeId } from '$lib/stores/charge';
	import { api } from '$lib/api/client';
	import type { BusinessScenario, DemoScenario, SelectedShipmentOverride, ShippingAddress } from '$lib/types';

	// Redirect if no customer ID
	$: if (!$customerId) {
		goto('/shop/home');
	}

	let showCheckout = false;
	let processing = false;
	let error = '';
	let paymentStatus = '';
	let cardNumber = '4242424242424242';
	let scenario: DemoScenario = 'NORMAL';
	let businessScenario: BusinessScenario = 'NORMAL';

	// Mirrors scripts/scenarios/{margin-spike,sla-breach}/1-submit-order.sh exactly.
	function selectedShipmentFor(bs: BusinessScenario): SelectedShipmentOverride | undefined {
		if (bs === 'MARGIN_SPIKE') return { paidPriceCents: 1 };
		if (bs === 'SLA_BREACH') return { paidPriceCents: 995, deliveryDays: 0 };
		return undefined;
	}

	// Canned addresses must match an entry in enablements-api's shipping fixture
	// (java/enablements/enablements-api/src/main/resources/fixtures/shipping-fixtures.json)
	// or fulfillment's address verification rejects the order outright.
	const CANNED_ADDRESSES: ShippingAddress[] = [
		{ street: '200 N Spring St', city: 'Los Angeles', state: 'CA', postalCode: '90012', country: 'US' },
		{ street: '301 Congress Ave', city: 'Austin', state: 'TX', postalCode: '78701', country: 'US' },
		{ street: '401 5th Ave', city: 'Seattle', state: 'WA', postalCode: '98104', country: 'US' },
		{ street: '11 Wall St', city: 'New York', state: 'NY', postalCode: '10005', country: 'US' }
	];

	const TEST_CARDS: Array<{ number: string; label: string }> = [
		{ number: '4242424242424242', label: 'Authorizes and captures normally' },
		{ number: '4000000000000002', label: 'Declined' },
		{ number: '4000000000009995', label: 'Insufficient funds' },
		{ number: '4000000000000069', label: 'Expired card' },
		{ number: '4000000000000259', label: 'Authorizes, then capture fails' }
	];

	// Shipping address form, prefilled with a random canned address
	let shippingAddress: ShippingAddress = randomAddress();

	function randomAddress(): ShippingAddress {
		return { ...CANNED_ADDRESSES[Math.floor(Math.random() * CANNED_ADDRESSES.length)] };
	}

	function useTestCard(number: string) {
		cardNumber = number;
	}

	function formatPrice(priceCents: number): string {
		return `$${(priceCents / 100).toFixed(2)}`;
	}

	function removeItem(itemId: string) {
		cart.removeItem(itemId);
	}

	function updateQuantity(itemId: string, quantity: number) {
		cart.updateQuantity(itemId, quantity);
	}

	function handlePlaceOrder() {
		if ($cart.length === 0) {
			error = 'Your cart is empty';
			return;
		}
		shippingAddress = randomAddress();
		showCheckout = true;
	}

	async function handleSubmitOrder() {
		if (processing) return;

		if (!shippingAddress.street || !shippingAddress.city || !shippingAddress.state || !shippingAddress.postalCode) {
			error = 'Please fill in all shipping address fields';
			return;
		}
		if (!cardNumber.trim()) {
			error = 'Please enter a card number';
			return;
		}

		processing = true;
		error = '';
		paymentStatus = '';

		try {
			const order = await api.submitCommerceOrder(
				$customerId,
				$cart.map((item) => ({ itemId: item.itemId, quantity: item.quantity })),
				shippingAddress,
				scenario,
				selectedShipmentFor(businessScenario),
				businessScenario === 'INVALID_ORDER'
			);
			currentOrderId.set(order.orderId);

			const charge = await api.createCharge(order.orderId, $customerId, $cartTotal, cardNumber.trim());
			currentChargeId.set(charge.chargeId);
			paymentStatus = charge.status;

			if (charge.status === 'DECLINED' || charge.status === 'INSUFFICIENT_FUNDS' || charge.status === 'EXPIRED_CARD') {
				error = `Payment ${charge.status.toLowerCase().replace('_', ' ')}${charge.declineReason ? ': ' + charge.declineReason : ''}. Please try a different card.`;
				processing = false;
				return;
			}

			cart.clear();
			goto('/shop/orders');
		} catch (err) {
			error = 'Failed to process order. Please try again.';
			processing = false;
			console.error(err);
		}
	}
</script>

<svelte:head>
	<title>Shopping Cart - Temporal OMS</title>
</svelte:head>

<!-- Floating demo scenario selector -->
<div class="fixed bottom-4 right-4 z-50 bg-white rounded-lg shadow-xl border border-gray-200 p-4 w-72">
	<h4 class="text-sm font-semibold text-gray-900 mb-2">Demo Scenario</h4>
	<select
		bind:value={scenario}
		class="w-full text-sm border border-gray-300 rounded-lg px-3 py-2"
	>
		<option value="NORMAL">Normal (commerce, then payment)</option>
		<option value="PAYMENT_BEFORE_COMMERCE">Payment before commerce</option>
		<option value="MISSING_COMMERCE_EVENT">Missing commerce event</option>
		<option value="MISSING_PAYMENT_EVENT">Missing payment event</option>
	</select>
	<p class="mt-2 text-xs text-gray-500">
		Governs how this order's webhook events are delivered by PublishCartOrders.
	</p>

	<h4 class="text-sm font-semibold text-gray-900 mt-4 mb-2">Business Scenario</h4>
	<select
		bind:value={businessScenario}
		class="w-full text-sm border border-gray-300 rounded-lg px-3 py-2"
	>
		<option value="NORMAL">Normal</option>
		<option value="MARGIN_SPIKE">Margin spike (forces alternate warehouse)</option>
		<option value="SLA_BREACH">SLA breach (same-day, no carrier can meet it)</option>
		<option value="INVALID_ORDER">Invalid order (fails validation)</option>
	</select>
	<p class="mt-2 text-xs text-gray-500">
		Triggers the same conditions as scripts/scenarios/{'{'}margin-spike,sla-breach,invalid-order{'}'},
		through this checkout instead of the standalone script.
	</p>
</div>

<div class="max-w-4xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
	<h1 class="text-3xl font-bold text-gray-900 mb-8">Shopping Cart</h1>

	{#if $cart.length === 0}
		<div class="bg-white rounded-lg shadow-md p-12 text-center">
			<svg
				class="w-16 h-16 text-gray-400 mx-auto mb-4"
				fill="none"
				stroke="currentColor"
				viewBox="0 0 24 24"
			>
				<path
					stroke-linecap="round"
					stroke-linejoin="round"
					stroke-width="2"
					d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z"
				/>
			</svg>
			<h2 class="text-xl font-semibold text-gray-900 mb-2">Your cart is empty</h2>
			<p class="text-gray-600 mb-6">Add some items from our catalog to get started</p>
			<a
				href="/shop/clothing"
				class="inline-flex items-center bg-primary-600 text-white px-6 py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors"
			>
				Continue Shopping
			</a>
		</div>
	{:else}
		<div class="grid gap-8 lg:grid-cols-3">
			<!-- Cart Items -->
			<div class="lg:col-span-2 space-y-4">
				{#each $cart as item}
					<div class="bg-white rounded-lg shadow-md p-6 flex items-center space-x-4">
						<div class="w-24 h-24 bg-gradient-to-br from-gray-100 to-gray-200 rounded-lg flex items-center justify-center flex-shrink-0">
							<svg
								class="w-12 h-12 text-gray-400"
								fill="none"
								stroke="currentColor"
								viewBox="0 0 24 24"
							>
								<path
									stroke-linecap="round"
									stroke-linejoin="round"
									stroke-width="1.5"
									d="M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01"
								/>
							</svg>
						</div>
						<div class="flex-1 min-w-0">
							<h3 class="text-lg font-semibold text-gray-900">{item.name}</h3>
							<p class="text-gray-600">{formatPrice(item.priceCents)}</p>
						</div>
						<div class="flex items-center space-x-3">
							<select
								value={item.quantity}
								onchange={(e) => updateQuantity(item.itemId, parseInt(e.currentTarget.value))}
								class="border border-gray-300 rounded-lg px-3 py-2"
							>
								{#each Array(10) as _, i}
									<option value={i + 1}>{i + 1}</option>
								{/each}
							</select>
							<button
								onclick={() => removeItem(item.itemId)}
								class="text-red-600 hover:text-red-700 transition-colors p-2"
								aria-label="Remove item"
							>
								<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path
										stroke-linecap="round"
										stroke-linejoin="round"
										stroke-width="2"
										d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
									/>
								</svg>
							</button>
						</div>
					</div>
				{/each}
			</div>

			<!-- Order Summary -->
			<div class="lg:col-span-1">
				<div class="bg-white rounded-lg shadow-md p-6 sticky top-4">
					<h2 class="text-xl font-semibold text-gray-900 mb-4">Order Summary</h2>
					<div class="space-y-3 mb-6">
						<div class="flex justify-between text-gray-600">
							<span>Subtotal</span>
							<span>{formatPrice($cartTotal)}</span>
						</div>
						<div class="flex justify-between text-gray-600">
							<span>Shipping</span>
							<span>TBD</span>
						</div>
						<div class="border-t pt-3 flex justify-between text-lg font-bold text-gray-900">
							<span>Total</span>
							<span>{formatPrice($cartTotal)}</span>
						</div>
					</div>

					{#if !showCheckout}
						<button
							onclick={handlePlaceOrder}
							class="w-full bg-primary-600 text-white px-6 py-3 rounded-lg font-semibold hover:bg-primary-700 transition-colors"
						>
							Place Order
						</button>
					{/if}
				</div>
			</div>
		</div>

		<!-- Checkout Form (shown after Place Order) -->
		{#if showCheckout}
			<div class="mt-8 bg-white rounded-lg shadow-md p-8">
				<h2 class="text-2xl font-semibold text-gray-900 mb-6">Complete Your Order</h2>

				<!-- Shipping Address Form -->
				<div class="mb-8">
					<h3 class="text-lg font-semibold text-gray-900 mb-4">Shipping Address</h3>
					<div class="grid gap-4 sm:grid-cols-2">
						<div class="sm:col-span-2">
							<label for="street" class="block text-sm font-medium text-gray-700 mb-1">
								Street Address
							</label>
							<input
								type="text"
								id="street"
								bind:value={shippingAddress.street}
								class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
								placeholder="123 Main St"
								required
							/>
						</div>
						<div>
							<label for="city" class="block text-sm font-medium text-gray-700 mb-1">
								City
							</label>
							<input
								type="text"
								id="city"
								bind:value={shippingAddress.city}
								class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
								required
							/>
						</div>
						<div>
							<label for="state" class="block text-sm font-medium text-gray-700 mb-1">
								State
							</label>
							<input
								type="text"
								id="state"
								bind:value={shippingAddress.state}
								class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
								maxlength="2"
								placeholder="CA"
								required
							/>
						</div>
						<div>
							<label for="postalCode" class="block text-sm font-medium text-gray-700 mb-1">
								Postal Code
							</label>
							<input
								type="text"
								id="postalCode"
								bind:value={shippingAddress.postalCode}
								class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
								required
							/>
						</div>
						<div>
							<label for="country" class="block text-sm font-medium text-gray-700 mb-1">
								Country
							</label>
							<input
								type="text"
								id="country"
								bind:value={shippingAddress.country}
								class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
								readonly
							/>
						</div>
					</div>
				</div>

				<!-- Card Number (Payments Processor simulator) -->
				<div class="mb-6">
					<h3 class="text-lg font-semibold text-gray-900 mb-4">Payment Information</h3>
					<label for="cardNumber" class="block text-sm font-medium text-gray-700 mb-1">
						Card Number
					</label>
					<input
						type="text"
						id="cardNumber"
						bind:value={cardNumber}
						class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent font-mono"
						placeholder="4242424242424242"
						required
					/>
					<p class="mt-2 mb-2 text-sm text-gray-500">Or click a test card to autofill it:</p>
					<div class="space-y-2">
						{#each TEST_CARDS as testCard}
							<button
								type="button"
								onclick={() => useTestCard(testCard.number)}
								class="w-full flex items-center justify-between px-4 py-2 border rounded-lg text-sm text-left transition-colors {cardNumber === testCard.number ? 'border-primary-500 bg-primary-50' : 'border-gray-300 hover:bg-gray-50'}"
							>
								<code class="font-mono">{testCard.number}</code>
								<span class="text-gray-600">{testCard.label}</span>
							</button>
						{/each}
					</div>
				</div>

				{#if paymentStatus && !error}
					<div class="mb-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
						<p class="text-sm text-blue-800">Payment status: {paymentStatus}</p>
					</div>
				{/if}

				{#if error}
					<div class="mb-4 p-4 bg-red-50 border border-red-200 rounded-lg">
						<p class="text-sm text-red-800">{error}</p>
					</div>
				{/if}

				<button
					onclick={handleSubmitOrder}
					disabled={processing}
					class="w-full bg-primary-600 text-white px-6 py-4 rounded-lg font-semibold text-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
				>
					{processing ? 'Processing...' : `Complete Order - ${formatPrice($cartTotal)}`}
				</button>
			</div>
		{/if}
	{/if}
</div>

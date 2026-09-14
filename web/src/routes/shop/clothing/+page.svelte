<script lang="ts">
	import { goto } from '$app/navigation';
	import { cart, cartCount } from '$lib/stores/cart';
	import { customerId } from '$lib/stores/customer';
	import type { PageData } from './$types';

	export let data: PageData;

	// Redirect if no customer ID
	$: if (!$customerId) {
		goto('/shop/home');
	}

	function addToCart(item: typeof data.items[0]) {
		cart.addItem(item);
	}

	function formatPrice(priceCents: number): string {
		return `$${(priceCents / 100).toFixed(2)}`;
	}
</script>

<svelte:head>
	<title>Shop Clothing - Temporal OMS</title>
</svelte:head>

<div class="max-w-7xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
	<!-- Header -->
	<div class="flex items-center justify-between mb-8">
		<div>
			<h1 class="text-3xl font-bold text-gray-900">Clothing Catalog</h1>
			<p class="mt-1 text-gray-600">Browse our collection of {data.items.length} items</p>
		</div>
		<a
			href="/shop/cart"
			class="relative inline-flex items-center bg-primary-600 text-white px-6 py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors"
		>
			<svg
				class="w-5 h-5 mr-2"
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
			View Cart
			{#if $cartCount > 0}
				<span class="absolute -top-2 -right-2 bg-red-500 text-white text-xs font-bold rounded-full w-6 h-6 flex items-center justify-center">
					{$cartCount}
				</span>
			{/if}
		</a>
	</div>

	<!-- Items Grid -->
	{#if data.items.length === 0}
		<div class="text-center py-12">
			<p class="text-gray-500">No items available at the moment.</p>
		</div>
	{:else}
		<div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
			{#each data.items as item}
				<div class="bg-white rounded-lg shadow-md overflow-hidden hover:shadow-lg transition-shadow">
					<!-- Image placeholder -->
					<div class="w-full h-64 bg-gradient-to-br from-gray-100 to-gray-200 flex items-center justify-center">
						<svg
							class="w-24 h-24 text-gray-400"
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

					<div class="p-4">
						<h3 class="text-lg font-semibold text-gray-900 mb-1">{item.name}</h3>
						<p class="text-sm text-gray-600 mb-3 line-clamp-2">{item.description}</p>
						{#if item.availableStock !== undefined}
							<p class="text-xs text-gray-500 mb-3">
								{item.availableStock > 0 ? `${item.availableStock} in stock` : 'Out of stock'}
							</p>
						{/if}
						<div class="flex items-center justify-between">
							<span class="text-2xl font-bold text-primary-600">
								{formatPrice(item.priceCents)}
							</span>
							<button
								onclick={() => addToCart(item)}
								disabled={item.availableStock === 0}
								class="bg-primary-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
							>
								Add to Cart
							</button>
						</div>
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>

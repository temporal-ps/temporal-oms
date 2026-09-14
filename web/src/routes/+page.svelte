<script lang="ts">
	import { goto } from '$app/navigation';
</script>

<svelte:head>
	<title>Temporal OMS - Home</title>
</svelte:head>

<div class="max-w-4xl mx-auto px-4 py-16 sm:px-6 lg:px-8">
	<div class="text-center">
		<h1 class="text-4xl font-bold text-gray-900 sm:text-5xl">
			Welcome to Temporal OMS
		</h1>
		<p class="mt-4 text-xl text-gray-600">
			A distributed order management system powered by Temporal workflows
		</p>

		<div class="mt-12 grid gap-8 md:grid-cols-2">
			<div class="bg-white p-8 rounded-lg shadow-md">
				<div class="w-16 h-16 bg-primary-100 rounded-full flex items-center justify-center mx-auto">
					<svg
						class="w-8 h-8 text-primary-600"
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
				<h2 class="mt-6 text-2xl font-semibold text-gray-900">Shop</h2>
				<p class="mt-4 text-gray-600">
					Browse our clothing catalog and place orders
				</p>
				<button
					onclick={() => goto('/shop/home')}
					class="mt-6 w-full bg-primary-600 text-white px-6 py-3 rounded-lg font-medium hover:bg-primary-700 transition-colors"
				>
					Start Shopping
				</button>
			</div>

			<div class="bg-white p-8 rounded-lg shadow-md">
				<div class="w-16 h-16 bg-primary-100 rounded-full flex items-center justify-center mx-auto">
					<svg
						class="w-8 h-8 text-primary-600"
						fill="none"
						stroke="currentColor"
						viewBox="0 0 24 24"
					>
						<path
							stroke-linecap="round"
							stroke-linejoin="round"
							stroke-width="2"
							d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
						/>
					</svg>
				</div>
				<h2 class="mt-6 text-2xl font-semibold text-gray-900">API Documentation</h2>
				<p class="mt-4 text-gray-600">
					Explore the REST API and webhook endpoints
				</p>
				<a
					href="/api/docs"
					target="_blank"
					class="mt-6 w-full inline-block text-center bg-gray-100 text-gray-900 px-6 py-3 rounded-lg font-medium hover:bg-gray-200 transition-colors"
				>
					View Swagger UI
				</a>
			</div>
		</div>

		<div class="mt-16 bg-blue-50 rounded-lg p-8">
			<h3 class="text-lg font-semibold text-gray-900">Architecture</h3>
			<p class="mt-2 text-gray-600">
				This system demonstrates Domain-Driven Design with 3 Temporal namespaces:
			</p>
			<div class="mt-6 grid gap-4 sm:grid-cols-3 text-sm">
				<div class="bg-white p-4 rounded-lg">
					<div class="font-semibold text-primary-700">Apps</div>
					<div class="text-gray-600 mt-1">Orchestration</div>
				</div>
				<div class="bg-white p-4 rounded-lg">
					<div class="font-semibold text-primary-700">Processing</div>
					<div class="text-gray-600 mt-1">Order enrichment</div>
				</div>
				<div class="bg-white p-4 rounded-lg">
					<div class="font-semibold text-primary-700">Fulfillment</div>
					<div class="text-gray-600 mt-1">AI-powered</div>
				</div>
			</div>
		</div>
	</div>
</div>

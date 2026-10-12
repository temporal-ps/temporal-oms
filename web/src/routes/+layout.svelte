<script lang="ts">
	import '../app.css';
	import { page } from '$app/stores';

	const NAV_LINKS = [
		{ label: 'Home', href: '/', match: (path: string) => path === '/' },
		{
			label: 'Shop',
			href: '/shop/home',
			match: (path: string) => path.startsWith('/shop') && !path.startsWith('/shop/orders')
		},
		{ label: 'Orders', href: '/shop/orders', match: (path: string) => path.startsWith('/shop/orders') },
		{ label: 'Admin', href: '/admin', match: (path: string) => path.startsWith('/admin') }
	];

	$: isActive = (match: (path: string) => boolean) => match($page.url.pathname);
</script>

<div class="min-h-screen flex flex-col">
	<!-- Header -->
	<header class="bg-white shadow-sm">
		<nav class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
			<div class="flex items-center justify-between">
				<a href="/" class="flex items-center space-x-2">
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
							d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z"
						/>
					</svg>
					<span class="text-xl font-bold text-gray-900">Temporal OMS</span>
				</a>

				<div class="flex items-center space-x-4">
					{#each NAV_LINKS as link}
						<a
							href={link.href}
							class="transition-colors {isActive(link.match)
								? 'text-gray-900 font-medium'
								: 'text-gray-600 hover:text-gray-900'}"
						>
							{link.label}
						</a>
					{/each}
				</div>
			</div>
		</nav>
	</header>

	<!-- Main content -->
	<main class="flex-1">
		<slot />
	</main>

	<!-- Footer -->
	<footer class="bg-white border-t mt-auto">
		<div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
			<p class="text-center text-gray-500 text-sm">
				Temporal Order Management System - Powered by Temporal Workflows
			</p>
		</div>
	</footer>
</div>

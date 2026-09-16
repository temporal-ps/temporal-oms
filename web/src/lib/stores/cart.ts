import { writable, derived } from 'svelte/store';
import { browser } from '$app/environment';
import type { CartItem, ClothingItem } from '$lib/types';

// Custom cart store with localStorage persistence
function createCartStore() {
	// Load initial state from localStorage
	const initialCart: CartItem[] = browser
		? JSON.parse(localStorage.getItem('cart') || '[]')
		: [];

	const { subscribe, set, update } = writable<CartItem[]>(initialCart);

	// Persist to localStorage on changes
	if (browser) {
		subscribe((value) => {
			localStorage.setItem('cart', JSON.stringify(value));
		});
	}

	return {
		subscribe,
		addItem: (item: ClothingItem) => {
			update((items) => {
				const existing = items.find((i) => i.itemId === item.itemId);
				if (existing) {
					// Increment quantity
					return items.map((i) =>
						i.itemId === item.itemId ? { ...i, quantity: i.quantity + 1 } : i
					);
				} else {
					// Add new item
					return [...items, { ...item, quantity: 1 }];
				}
			});
		},
		removeItem: (itemId: string) => {
			update((items) => items.filter((i) => i.itemId !== itemId));
		},
		updateQuantity: (itemId: string, quantity: number) => {
			if (quantity <= 0) {
				update((items) => items.filter((i) => i.itemId !== itemId));
			} else {
				update((items) =>
					items.map((i) => (i.itemId === itemId ? { ...i, quantity } : i))
				);
			}
		},
		clear: () => {
			set([]);
			if (browser) {
				localStorage.removeItem('cart');
			}
		}
	};
}

// Export cart store
export const cart = createCartStore();

// Derived store for cart total
export const cartTotal = derived(cart, ($cart) =>
	$cart.reduce((total, item) => total + item.priceCents * item.quantity, 0)
);

// Derived store for cart item count
export const cartCount = derived(cart, ($cart) =>
	$cart.reduce((count, item) => count + item.quantity, 0)
);

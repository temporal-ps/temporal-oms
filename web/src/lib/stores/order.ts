import { writable } from 'svelte/store';
import { browser } from '$app/environment';

// Current order ID store with localStorage persistence
function createOrderStore() {
	const initialOrderId = browser ? localStorage.getItem('orderId') || '' : '';

	const { subscribe, set } = writable<string>(initialOrderId);

	// Persist to localStorage
	if (browser) {
		subscribe((value) => {
			if (value) {
				localStorage.setItem('orderId', value);
			} else {
				localStorage.removeItem('orderId');
			}
		});
	}

	return {
		subscribe,
		set: (orderId: string) => {
			set(orderId);
		},
		clear: () => {
			set('');
			if (browser) {
				localStorage.removeItem('orderId');
			}
		}
	};
}

export const orderId = createOrderStore();

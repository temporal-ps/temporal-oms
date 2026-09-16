import { writable } from 'svelte/store';
import { browser } from '$app/environment';

// Customer store with localStorage persistence
function createCustomerStore() {
	const initialCustomerId = browser ? localStorage.getItem('customerId') || '' : '';

	const { subscribe, set } = writable<string>(initialCustomerId);

	// Persist to localStorage
	if (browser) {
		subscribe((value) => {
			if (value) {
				localStorage.setItem('customerId', value);
			} else {
				localStorage.removeItem('customerId');
			}
		});
	}

	return {
		subscribe,
		set: (customerId: string) => {
			set(customerId);
		},
		clear: () => {
			set('');
			if (browser) {
				localStorage.removeItem('customerId');
			}
		}
	};
}

export const customerId = createCustomerStore();

import { writable } from 'svelte/store';
import { browser } from '$app/environment';

// Current charge ID store with localStorage persistence
function createChargeStore() {
	const initialChargeId = browser ? localStorage.getItem('chargeId') || '' : '';

	const { subscribe, set } = writable<string>(initialChargeId);

	// Persist to localStorage
	if (browser) {
		subscribe((value) => {
			if (value) {
				localStorage.setItem('chargeId', value);
			} else {
				localStorage.removeItem('chargeId');
			}
		});
	}

	return {
		subscribe,
		set: (chargeId: string) => {
			set(chargeId);
		},
		clear: () => {
			set('');
			if (browser) {
				localStorage.removeItem('chargeId');
			}
		}
	};
}

export const chargeId = createChargeStore();

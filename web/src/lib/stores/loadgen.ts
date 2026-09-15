import { writable } from 'svelte/store';
import { browser } from '$app/environment';

export interface LoadGenSession {
	enablementId: string;
	startedAt: number;
}

// Current load-generator session (enablementId + start time), persisted so a
// page refresh mid-demo doesn't lose track of the running workflow.
function createLoadGenStore() {
	const initial: LoadGenSession | null = browser
		? JSON.parse(localStorage.getItem('loadGenSession') || 'null')
		: null;

	const { subscribe, set } = writable<LoadGenSession | null>(initial);

	if (browser) {
		subscribe((value) => {
			if (value) {
				localStorage.setItem('loadGenSession', JSON.stringify(value));
			} else {
				localStorage.removeItem('loadGenSession');
			}
		});
	}

	return {
		subscribe,
		set: (enablementId: string) => {
			set({ enablementId, startedAt: Date.now() });
		},
		clear: () => {
			set(null);
		}
	};
}

export const loadGenSession = createLoadGenStore();

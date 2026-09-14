import { api } from '$lib/api/client';
import type { PageLoad } from './$types';

export const load: PageLoad = async () => {
	try {
		const items = await api.getCatalog();
		return { items };
	} catch (error) {
		console.error('Failed to load catalog:', error);
		return { items: [] };
	}
};

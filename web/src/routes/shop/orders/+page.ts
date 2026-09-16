import { api } from '$lib/api/client';
import { get } from 'svelte/store';
import { customerId } from '$lib/stores/customer';
import { orderId } from '$lib/stores/order';
import { chargeId } from '$lib/stores/charge';
import { redirect } from '@sveltejs/kit';
import type { PageLoad } from './$types';

// The Commerce App / Payments Processor backends expose only single-entity
// status lookups (getState() per order/charge), not a cross-order listing
// for a customer; this page tracks the current order this browser placed.
export const load: PageLoad = async () => {
	const currentCustomerId = get(customerId);
	const currentOrderId = get(orderId);
	const currentChargeId = get(chargeId);

	if (!currentCustomerId) {
		throw redirect(302, '/shop/home');
	}
	if (!currentOrderId) {
		return { customerId: currentCustomerId, order: null, charge: null };
	}

	try {
		const [order, charge] = await Promise.all([
			api.getOrder(currentOrderId),
			currentChargeId ? api.getCharge(currentChargeId) : Promise.resolve(null)
		]);
		return { customerId: currentCustomerId, order, charge };
	} catch (error) {
		console.error('Failed to load order:', error);
		return { customerId: currentCustomerId, order: null, charge: null };
	}
};

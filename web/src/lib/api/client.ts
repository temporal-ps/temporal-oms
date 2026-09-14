// API client for the Commerce App / Payments Processor backends (enablements-api)
import type {
	ClothingItem,
	CommerceOrderResponse,
	DemoScenario,
	PaymentChargeResponse,
	SelectedShipmentOverride,
	ShippingAddress
} from '$lib/types';

const API_BASE = '/api/v1/integrations';

class ApiError extends Error {
	constructor(
		public status: number,
		message: string
	) {
		super(message);
		this.name = 'ApiError';
	}
}

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
	const response = await fetch(url, {
		...options,
		headers: {
			'Content-Type': 'application/json',
			...options?.headers
		}
	});

	if (!response.ok) {
		throw new ApiError(response.status, `API error: ${response.statusText}`);
	}

	return response.json();
}

function toCommerceAddress(address: ShippingAddress) {
	return {
		easypost: {
			street1: address.street,
			city: address.city,
			state: address.state,
			zip: address.postalCode,
			country: address.country
		}
	};
}

export const api = {
	// Get the commerce catalog (static reference data + live stock)
	async getCatalog(): Promise<ClothingItem[]> {
		const data = await fetchJson<{ items: ClothingItem[] }>(`${API_BASE}/commerce/catalog`);
		return data.items;
	},

	// Place a commerce order; the order ID is assigned server-side
	async submitCommerceOrder(
		customerId: string,
		items: Array<{ itemId: string; quantity: number }>,
		shippingAddress: ShippingAddress,
		scenario: DemoScenario,
		selectedShipment?: SelectedShipmentOverride,
		forceInvalidOrderId?: boolean
	): Promise<CommerceOrderResponse> {
		return fetchJson(`${API_BASE}/commerce/orders`, {
			method: 'POST',
			body: JSON.stringify({
				customerId,
				items,
				shippingAddress: toCommerceAddress(shippingAddress),
				scenarioOptions: { scenario },
				...(selectedShipment
					? {
							selectedShipment: {
								paidPrice: { units: selectedShipment.paidPriceCents, currency: 'USD' },
								...(selectedShipment.deliveryDays !== undefined
									? { easypost: { selectedRate: { deliveryDays: selectedShipment.deliveryDays } } }
									: {})
							}
						}
					: {}),
				...(forceInvalidOrderId ? { forceInvalidOrderId: true } : {})
			})
		});
	},

	// Get a single order's state
	async getOrder(orderId: string): Promise<CommerceOrderResponse> {
		return fetchJson(`${API_BASE}/commerce/orders/${orderId}`);
	},

	// Authorize (and schedule auto-capture for) a charge, driven by card number
	async createCharge(
		orderId: string,
		customerId: string,
		amountCents: number,
		cardNumber: string
	): Promise<PaymentChargeResponse> {
		return fetchJson(`${API_BASE}/payments/charges`, {
			method: 'POST',
			body: JSON.stringify({ orderId, customerId, amountCents, cardNumber })
		});
	},

	// Get a single charge's state
	async getCharge(chargeId: string): Promise<PaymentChargeResponse> {
		return fetchJson(`${API_BASE}/payments/charges/${chargeId}`);
	},

	// Cancel an in-flight order via apps-api's apps.Order.cancelOrder Update
	// (same effect as scripts/scenarios/cancel-order, from the UI instead of the CLI).
	async cancelOrder(orderId: string, reason: string, cancelledBy: string): Promise<void> {
		const response = await fetch(`/api/v1/commerce-app/orders/${orderId}/cancel`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ reason, cancelledBy })
		});
		if (!response.ok) {
			throw new ApiError(response.status, `Failed to cancel order: ${response.statusText}`);
		}
	}
};

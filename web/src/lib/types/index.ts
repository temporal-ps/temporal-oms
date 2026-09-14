// TypeScript types for the application

export interface ClothingItem {
	itemId: string;
	name: string;
	description: string;
	priceCents: number;
	imageUrl: string;
	availableStock?: number;
}

export interface CartItem extends ClothingItem {
	quantity: number;
}

export interface ShippingAddress {
	street: string;
	city: string;
	state: string;
	postalCode: string;
	country: string;
}

// Matches acme.enablements.domain.v1.DemoScenario — governs webhook delivery timing/omission
export type DemoScenario =
	| 'NORMAL'
	| 'PAYMENT_BEFORE_COMMERCE'
	| 'MISSING_COMMERCE_EVENT'
	| 'MISSING_PAYMENT_EVENT';

// Business-logic conditions from scripts/scenarios/*, made selectable through the
// Commerce App simulator instead of only via the standalone shell scripts.
export type BusinessScenario = 'NORMAL' | 'MARGIN_SPIKE' | 'SLA_BREACH' | 'INVALID_ORDER';

export interface SelectedShipmentOverride {
	paidPriceCents: number;
	deliveryDays?: number;
}

// Matches acme.enablements.domain.v1.CommerceOrderState (fields the UI reads)
export interface CommerceOrderResponse {
	orderId: string;
	customerId: string;
	status: string;
	items: Array<{ itemId: string; quantity: number }>;
	placedAt?: string;
}

// Matches acme.enablements.domain.v1.PaymentChargeState (fields the UI reads)
export interface PaymentChargeResponse {
	chargeId: string;
	orderId: string;
	status: string; // AUTHORIZED | CAPTURED | VOIDED | CAPTURE_FAILED | DECLINED | INSUFFICIENT_FUNDS | EXPIRED_CARD
	declineReason?: string;
}

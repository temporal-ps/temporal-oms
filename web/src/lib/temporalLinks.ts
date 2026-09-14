import { env } from '$env/dynamic/public';

const TEMPORAL_UI_BASE_URL = env.PUBLIC_TEMPORAL_UI_BASE_URL || 'http://localhost:8233';

// enablements' own namespace defaults to "default" locally (spring.temporal.namespace in
// acme.enablements.yaml); Commerce/Payments workflows run there.
const ENABLEMENTS_NAMESPACE = env.PUBLIC_TEMPORAL_ENABLEMENTS_NAMESPACE || 'default';

export function temporalWorkflowUrl(namespace: string, workflowId: string): string {
	return `${TEMPORAL_UI_BASE_URL}/namespaces/${encodeURIComponent(namespace)}/workflows/${encodeURIComponent(workflowId)}`;
}

export interface OrderTrackingLink {
	label: string;
	namespace: string;
	workflowId: string;
	url: string;
}

// Every stage's workflow ID is the order ID except the payment charge, which uses
// its own chargeId. See specs/fulfillment-order/fulfillment-order-workflow/spec.md
// and java/processing/processing-core/.../services/ProcessingImpl.java.
export function orderTrackingLinks(orderId: string, chargeId: string | null): OrderTrackingLink[] {
	const links: OrderTrackingLink[] = [
		{
			label: 'Commerce order',
			namespace: ENABLEMENTS_NAMESPACE,
			workflowId: orderId,
			url: temporalWorkflowUrl(ENABLEMENTS_NAMESPACE, orderId)
		}
	];
	if (chargeId) {
		links.push({
			label: 'Payment charge',
			namespace: ENABLEMENTS_NAMESPACE,
			workflowId: chargeId,
			url: temporalWorkflowUrl(ENABLEMENTS_NAMESPACE, chargeId)
		});
	}
	links.push(
		{ label: 'Order (apps)', namespace: 'apps', workflowId: orderId, url: temporalWorkflowUrl('apps', orderId) },
		{
			label: 'Processing',
			namespace: 'processing',
			workflowId: orderId,
			url: temporalWorkflowUrl('processing', orderId)
		},
		{
			label: 'Fulfillment',
			namespace: 'fulfillment',
			workflowId: orderId,
			url: temporalWorkflowUrl('fulfillment', orderId)
		}
	);
	return links;
}

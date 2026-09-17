// API client for the on-demand worker-version deployment endpoint on enablements-api
// (hosting.md Mode B). Independent of the load-generator endpoints in ./loadgen.ts:
// a deployment action never depends on load state, and starting load never depends on
// a deployment having happened.
const API_BASE = '/api/v1/enablements/deployments';

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

export type BoundedContext = 'apps' | 'processing' | 'fulfillment';

export interface PromoteRequest {
	version: string;
	buildId?: string;
	replicaCount?: number;
}

export interface DeployWorkerVersionResponse {
	workflowClass: string;
	currentVersionSet: boolean;
	describeOutput: string;
}

export const deploymentsApi = {
	async promote(boundedContext: BoundedContext, payload: PromoteRequest): Promise<DeployWorkerVersionResponse> {
		return fetchJson(`${API_BASE}/${boundedContext}`, {
			method: 'POST',
			body: JSON.stringify(payload)
		});
	}
};

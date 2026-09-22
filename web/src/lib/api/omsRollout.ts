// API client for the OMS-version-driven rollout control (hosting.md
// "OMS-Version-Driven Promotion"): pick a target OMS version and promote
// apps/processing/fulfillment together, instead of the three independent
// per-context promotions in ./deployments.ts.
const API_BASE = '/api/v1/enablements';

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

export interface OmsVersionRow {
	omsVersion: string;
	appsVersion: string;
	processingVersion: string;
	fulfillmentVersion: string;
	description: string;
	future: boolean;
}

export type RolloutStepStatus =
	| 'ROLLOUT_STEP_STATUS_UNSPECIFIED'
	| 'PENDING'
	| 'IN_PROGRESS'
	| 'SUCCEEDED'
	| 'FAILED'
	| 'SKIPPED';

export interface OmsVersionRolloutStep {
	boundedContext: string;
	targetVersion: string;
	status: RolloutStepStatus;
	errorMessage: string;
}

export interface OmsVersionRolloutState {
	rolloutId: string;
	omsVersion: string;
	overallStatus: RolloutStepStatus;
	steps: OmsVersionRolloutStep[];
	errorMessage: string;
}

export const omsRolloutApi = {
	async listVersions(): Promise<{ rows: OmsVersionRow[] }> {
		return fetchJson(`${API_BASE}/oms-versions`);
	},

	async start(omsVersion: string): Promise<OmsVersionRolloutState> {
		return fetchJson(`${API_BASE}/oms-rollouts`, {
			method: 'POST',
			body: JSON.stringify({ omsVersion })
		});
	},

	async getState(rolloutId: string): Promise<OmsVersionRolloutState> {
		return fetchJson(`${API_BASE}/oms-rollouts/${rolloutId}`);
	}
};

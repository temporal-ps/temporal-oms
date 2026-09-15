// API client for the load-generator (WorkerVersionEnablement) control endpoints on enablements-api
const API_BASE = '/api/v1/enablements/worker-version';

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

	if (response.status === 202) {
		return undefined as T;
	}

	return response.json();
}

// Standalone Activity execution status (runSubmissionLoop has no owning workflow, so there is
// no DemoPhase/active_versions -- those were WorkerVersionEnablement-workflow concepts).
export type ExecutionStatus = 'EXECUTION_STATUS_UNSPECIFIED' | 'RUNNING' | 'COMPLETED' | 'CANCELED' | 'FAILED';

export interface ScenarioWeight {
	scenario: 'NORMAL' | 'PAYMENT_BEFORE_COMMERCE' | 'MISSING_COMMERCE_EVENT' | 'MISSING_PAYMENT_EVENT';
	weight: number;
}

// BusinessScenario proto values carry a BUSINESS_SCENARIO_ prefix, unlike the
// unprefixed BusinessScenario type in $lib/types used by the cart's scenario
// picker. Do not conflate the two.
export interface BusinessScenarioWeight {
	scenario:
		| 'BUSINESS_SCENARIO_NORMAL'
		| 'BUSINESS_SCENARIO_MARGIN_SPIKE'
		| 'BUSINESS_SCENARIO_SLA_BREACH'
		| 'BUSINESS_SCENARIO_INVALID_ORDER';
	weight: number;
}

export interface StartLoadGeneratorRequest {
	orderCount: number;
	submitRatePerMin: number;
	timeout: string;
	scenarioWeights: ScenarioWeight[];
	businessScenarioWeights: BusinessScenarioWeight[];
}

export interface LoadGeneratorState {
	enablementId: string;
	status: ExecutionStatus;
	ordersSubmittedCount: number;
}

export const loadGenApi = {
	async start(payload: StartLoadGeneratorRequest): Promise<LoadGeneratorState> {
		return fetchJson(`${API_BASE}/start`, {
			method: 'POST',
			body: JSON.stringify(payload)
		});
	},

	async stop(enablementId: string, reason?: string): Promise<void> {
		await fetchJson(`${API_BASE}/${enablementId}/stop`, {
			method: 'POST',
			body: JSON.stringify({ reason })
		});
	},

	async getState(enablementId: string): Promise<LoadGeneratorState> {
		return fetchJson(`${API_BASE}/${enablementId}`);
	}
};

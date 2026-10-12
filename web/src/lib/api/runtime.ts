// API client for enablements-api's runtime probe. Worker-version promotion creates and
// deletes Kubernetes Deployments, so the Admin page uses this to disable it outside KinD/k3d.
export interface RuntimeInfo {
	kubernetes: boolean;
}

export const runtimeApi = {
	async get(): Promise<RuntimeInfo> {
		const response = await fetch('/api/v1/enablements/runtime');
		if (!response.ok) {
			throw new Error(`API error: ${response.status} ${response.statusText}`);
		}
		return response.json();
	}
};

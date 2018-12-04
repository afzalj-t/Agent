package org.eclipse.iofog.microservice;

import org.eclipse.iofog.connector_client.ConnectorConfig;

public class Receiver {
	private String microserviceUuid;
	private boolean isLocal;
	private ConnectorConfig routeConfig;

	public Receiver(String microserviceUuid, boolean isLocal, ConnectorConfig routeConfig) {
		this.microserviceUuid = microserviceUuid;
		this.isLocal = isLocal;
		this.routeConfig = routeConfig;
	}

	public String getMicroserviceUuid() {
		return microserviceUuid;
	}

	public void setMicroserviceUuid(String microserviceUuid) {
		this.microserviceUuid = microserviceUuid;
	}

	public boolean isLocal() {
		return isLocal;
	}

	public void setLocal(boolean local) {
		isLocal = local;
	}

	public ConnectorConfig getRouteConfig() {
		return routeConfig;
	}

	public void setRouteConfig(ConnectorConfig routeConfig) {
		this.routeConfig = routeConfig;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Receiver receiver = (Receiver) o;

		if (isLocal != receiver.isLocal) return false;
		if (!microserviceUuid.equals(receiver.microserviceUuid)) return false;
		return routeConfig != null ? routeConfig.equals(receiver.routeConfig) : receiver.routeConfig == null;
	}

	@Override
	public int hashCode() {
		int result = microserviceUuid.hashCode();
		result = 31 * result + (isLocal ? 1 : 0);
		result = 31 * result + (routeConfig != null ? routeConfig.hashCode() : 0);
		return result;
	}
}

/*
 * *******************************************************************************
 *  * Copyright (c) 2018-2020 Edgeworx, Inc.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */
package org.eclipse.iofog.microservice;

/**
 * represents Microservices port mappings
 *
 * @author saeid
 *
 */
public class PortMapping implements Comparable<PortMapping> {
	private final int outside;
	private final int inside;

	public PortMapping(int outside, int inside) {
		this.outside = outside;
		this.inside = inside;
	}

	public int getOutside() {
		return outside;
	}

	public int getInside() {
		return inside;
	}

	@Override
	public String toString() {
		return "{" + "outside='" + outside + '\'' + ", inside='" + inside + '\'' + '}';
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (other == null || getClass() != other.getClass()) return false;

		PortMapping o = (PortMapping) other;
		return this.outside == o.outside && this.inside == o.inside;
	}

	@Override
	public int compareTo(PortMapping o) {
		if (this.inside == o.inside) {
			return this.outside - o.outside;
		}

		return this.inside - o.inside;
	}
}
